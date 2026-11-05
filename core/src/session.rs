use subtle::ConstantTimeEq;

use crate::codec::{Reader, Writer};
use crate::document::{Manifest, Plan, Receipt};
use crate::pairing::Keys;
use crate::signing::{self, SIGNATURE_LEN, SigningKey, VERIFICATION_KEY_LEN};
use crate::stream::{Chunk, StreamOpener, StreamSealer};
use crate::{Error, Role};

const MANIFEST_CONTEXT: &[u8] = b"graphite/1 manifest";
const PLAN_CONTEXT: &[u8] = b"graphite/1 plan";
const RECEIPT_CONTEXT: &[u8] = b"graphite/1 receipt";

/// The hash a plan or receipt quotes to say which signed manifest it answers.
pub fn manifest_hash(signed_manifest: &[u8]) -> [u8; 32] {
    blake3::derive_key("graphite/1 manifest hash", signed_manifest)
}

/// The hash a receipt quotes to say which signed plan it follows.
pub fn plan_hash(signed_plan: &[u8]) -> [u8; 32] {
    blake3::derive_key("graphite/1 plan hash", signed_plan)
}

/// A paired session: one stream each way and the two signing keys that
/// pairing authenticated.
pub struct Session {
    role: Role,
    transcript_hash: [u8; 32],
    sealer: StreamSealer,
    opener: StreamOpener,
    signing_key: SigningKey,
    peer_key: Box<[u8; VERIFICATION_KEY_LEN]>,
}

impl Session {
    pub(crate) fn new(
        role: Role,
        keys: Keys,
        signing_key: SigningKey,
        peer_key: Box<[u8; VERIFICATION_KEY_LEN]>,
    ) -> Self {
        let (send, receive) = match role {
            Role::Source => (&keys.source_to_target, &keys.target_to_source),
            Role::Target => (&keys.target_to_source, &keys.source_to_target),
        };
        Session {
            role,
            transcript_hash: keys.transcript_hash,
            sealer: StreamSealer::new(send, role),
            opener: StreamOpener::new(receive, role.peer()),
            signing_key,
            peer_key,
        }
    }

    pub fn role(&self) -> Role {
        self.role
    }

    /// Identifies this session; the same on both phones.
    pub fn transcript_hash(&self) -> [u8; 32] {
        self.transcript_hash
    }

    /// Seals the next chunk to the peer: at most [`crate::CHUNK_SIZE`] bytes,
    /// `last` on the final one.
    pub fn seal(&mut self, plaintext: &[u8], last: bool) -> Result<Vec<u8>, Error> {
        self.sealer.seal(plaintext, last)
    }

    /// Opens the next chunk from the peer. Any failure ends the stream.
    pub fn open(&mut self, chunk: &[u8]) -> Result<Chunk, Error> {
        self.opener.open(chunk)
    }

    /// Call when the peer's input ends: fails unless its last chunk arrived.
    pub fn finish_receiving(&self) -> Result<(), Error> {
        self.opener.finish()
    }

    fn require(&self, role: Role) -> Result<(), Error> {
        if self.role == role {
            Ok(())
        } else {
            Err(Error::WrongRole)
        }
    }

    fn sign(&self, context: &[u8], body: Vec<u8>) -> Result<Vec<u8>, Error> {
        let message = [&self.transcript_hash[..], &body].concat();
        let signature = self.signing_key.sign(context, &message)?;
        Ok(Writer::new().bytes(&body).fixed(&signature).finish())
    }

    /// Verifies a signed document from the peer and returns its body.
    fn verify<'a>(&self, context: &[u8], signed: &'a [u8]) -> Result<&'a [u8], Error> {
        let mut r = Reader::new(signed);
        let body = r.bytes()?;
        let signature = r.array::<SIGNATURE_LEN>()?;
        r.end()?;
        let message = [&self.transcript_hash[..], body].concat();
        signing::verify(&self.peer_key, context, &message, &signature)?;
        Ok(body)
    }

    /// Source: signs the manifest for this session.
    pub fn sign_manifest(&self, manifest: &Manifest) -> Result<Vec<u8>, Error> {
        self.require(Role::Source)?;
        manifest.validate()?;
        self.sign(MANIFEST_CONTEXT, manifest.encode())
    }

    /// Target: checks the source's signature and the manifest's rules.
    pub fn verify_manifest(&self, signed: &[u8]) -> Result<Manifest, Error> {
        self.require(Role::Target)?;
        let manifest = Manifest::decode(self.verify(MANIFEST_CONTEXT, signed)?)?;
        manifest.validate()?;
        Ok(manifest)
    }

    /// Target: signs the plan for `signed_manifest`, after checking the
    /// manifest's signature, that the plan answers it, and the plan's rules.
    pub fn sign_plan(&self, plan: &Plan, signed_manifest: &[u8]) -> Result<Vec<u8>, Error> {
        self.require(Role::Target)?;
        let manifest = self.verify_manifest(signed_manifest)?;
        same(
            &plan.manifest_hash,
            &manifest_hash(signed_manifest),
            Error::ManifestMismatch,
        )?;
        plan.validate(&manifest)?;
        self.sign(PLAN_CONTEXT, plan.encode())
    }

    /// Source: checks the target's signature and that the plan answers
    /// `signed_manifest`, the manifest this side sent, entry by entry.
    pub fn verify_plan(&self, signed: &[u8], signed_manifest: &[u8]) -> Result<Plan, Error> {
        self.require(Role::Source)?;
        let plan = Plan::decode(self.verify(PLAN_CONTEXT, signed)?)?;
        let manifest = own_document(signed_manifest).and_then(Manifest::decode)?;
        same(
            &plan.manifest_hash,
            &manifest_hash(signed_manifest),
            Error::ManifestMismatch,
        )?;
        plan.validate(&manifest)?;
        Ok(plan)
    }

    /// Target: signs the receipt for `signed_manifest` and `signed_plan`
    /// (this side's own plan for that manifest).
    pub fn sign_receipt(
        &self,
        receipt: &Receipt,
        signed_manifest: &[u8],
        signed_plan: &[u8],
    ) -> Result<Vec<u8>, Error> {
        self.require(Role::Target)?;
        let manifest = self.verify_manifest(signed_manifest)?;
        let plan = own_document(signed_plan).and_then(Plan::decode)?;
        let hash = manifest_hash(signed_manifest);
        same(&plan.manifest_hash, &hash, Error::ManifestMismatch)?;
        same(&receipt.manifest_hash, &hash, Error::ManifestMismatch)?;
        same(
            &receipt.plan_hash,
            &plan_hash(signed_plan),
            Error::PlanMismatch,
        )?;
        plan.validate(&manifest)?;
        receipt.validate(&manifest, &plan)?;
        self.sign(RECEIPT_CONTEXT, receipt.encode())
    }

    /// Source: checks the target's signature and that the receipt answers
    /// `signed_manifest` (sent by this side) and follows `signed_plan`
    /// (verified by this side), entry by entry.
    pub fn verify_receipt(
        &self,
        signed: &[u8],
        signed_manifest: &[u8],
        signed_plan: &[u8],
    ) -> Result<Receipt, Error> {
        self.require(Role::Source)?;
        let receipt = Receipt::decode(self.verify(RECEIPT_CONTEXT, signed)?)?;
        let manifest = own_document(signed_manifest).and_then(Manifest::decode)?;
        same(
            &receipt.manifest_hash,
            &manifest_hash(signed_manifest),
            Error::ManifestMismatch,
        )?;
        same(
            &receipt.plan_hash,
            &plan_hash(signed_plan),
            Error::PlanMismatch,
        )?;
        let plan = Plan::decode(self.verify(PLAN_CONTEXT, signed_plan)?)?;
        same(
            &plan.manifest_hash,
            &manifest_hash(signed_manifest),
            Error::ManifestMismatch,
        )?;
        plan.validate(&manifest)?;
        receipt.validate(&manifest, &plan)?;
        Ok(receipt)
    }
}

/// The body of a document this side signed itself: parsed, not re-verified.
fn own_document(signed: &[u8]) -> Result<&[u8], Error> {
    let mut r = Reader::new(signed);
    let body = r.bytes()?;
    r.array::<SIGNATURE_LEN>()?;
    r.end()?;
    Ok(body)
}

fn same(a: &[u8; 32], b: &[u8; 32], error: Error) -> Result<(), Error> {
    if bool::from(a.ct_eq(b)) {
        Ok(())
    } else {
        Err(error)
    }
}

#[cfg(test)]
mod tests {
    //! The source re-checks what the target signed. The public API will not
    //! sign a plan or receipt that breaks the rules, so these sign one past it.

    use super::*;
    use crate::document::{
        FileDigest, ManifestEntry, MetadataChoice, NotCopiedReason, PlanEntry, PlanStatus,
        ReceiptEntry, ReceiptStatus, plan_totals,
    };
    use crate::{SourcePairing, TargetPairing};

    fn pair() -> (Session, Session) {
        let mut target = TargetPairing::new().unwrap();
        let mut source = SourcePairing::new().unwrap();
        let mut msg = target.hello();
        while let Some(reply) = source.handle(&msg).unwrap() {
            match target.handle(&reply).unwrap() {
                Some(next) => msg = next,
                None => break,
            }
        }
        source.confirm_code().unwrap();
        target.confirm_code().unwrap();
        (source.finish().unwrap(), target.finish().unwrap())
    }

    fn manifest() -> Manifest {
        let entry = |path: &str| ManifestEntry {
            path: path.into(),
            mime_type: "image/jpeg".into(),
            date_taken_ms: None,
            date_added_ms: None,
            modified_ms: 0,
            original: FileDigest {
                size: 5,
                blake3: [1; 32],
            },
            sent: FileDigest {
                size: 5,
                blake3: [1; 32],
            },
        };
        Manifest {
            metadata: MetadataChoice::KeepAll,
            stripper_version: "1".into(),
            files: vec![entry("DCIM/a.jpg"), entry("DCIM/b.jpg")],
            records: vec![],
        }
    }

    #[test]
    fn source_refuses_a_signed_plan_or_receipt_that_breaks_the_rules() {
        let (source, target) = pair();
        let signed_manifest = source.sign_manifest(&manifest()).unwrap();
        let hash = manifest_hash(&signed_manifest);

        let entries = vec![
            PlanEntry {
                index: 0,
                status: PlanStatus::ToCopy,
            },
            PlanEntry {
                index: 1,
                status: PlanStatus::Present,
            },
        ];
        let good = Plan {
            manifest_hash: hash,
            totals: plan_totals(&manifest(), &entries),
            entries,
        };
        let signed_plan = target.sign_plan(&good, &signed_manifest).unwrap();

        let mut duplicated = good.clone();
        duplicated.entries[1].index = 0;
        let mut wrong_totals = good.clone();
        wrong_totals.totals.bytes_to_copy = 0;
        for bad in [duplicated, wrong_totals] {
            let signed = target.sign(PLAN_CONTEXT, bad.encode()).unwrap();
            assert_eq!(
                source.verify_plan(&signed, &signed_manifest),
                Err(Error::InvalidPlan)
            );
        }

        let missing = Receipt {
            manifest_hash: hash,
            plan_hash: plan_hash(&signed_plan),
            entries: vec![ReceiptEntry {
                index: 0,
                status: ReceiptStatus::CopiedVerified,
                note: String::new(),
            }],
        };
        let signed = target.sign(RECEIPT_CONTEXT, missing.encode()).unwrap();
        assert_eq!(
            source.verify_receipt(&signed, &signed_manifest, &signed_plan),
            Err(Error::InvalidReceipt)
        );

        // Every entry present, but entry 0, which the plan has to copy, is
        // reported as unticked.
        let mut contradicts = missing;
        contradicts.entries[0].status = ReceiptStatus::NotCopied(NotCopiedReason::Unticked);
        contradicts.entries.push(ReceiptEntry {
            index: 1,
            status: ReceiptStatus::PresentVerified,
            note: String::new(),
        });
        let signed = target.sign(RECEIPT_CONTEXT, contradicts.encode()).unwrap();
        assert_eq!(
            source.verify_receipt(&signed, &signed_manifest, &signed_plan),
            Err(Error::InvalidReceipt)
        );
    }
}
