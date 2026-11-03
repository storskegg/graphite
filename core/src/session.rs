use subtle::ConstantTimeEq;

use crate::codec::{Reader, Writer};
use crate::document::{Manifest, Receipt};
use crate::pairing::Keys;
use crate::signing::{self, SIGNATURE_LEN, SigningKey, VERIFICATION_KEY_LEN};
use crate::stream::{Chunk, StreamOpener, StreamSealer};
use crate::{Error, Role};

const MANIFEST_CONTEXT: &[u8] = b"graphite/1 manifest";
const RECEIPT_CONTEXT: &[u8] = b"graphite/1 receipt";

/// The hash a receipt quotes to say which signed manifest it answers.
pub fn manifest_hash(signed_manifest: &[u8]) -> [u8; 32] {
    blake3::derive_key("graphite/1 manifest hash", signed_manifest)
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

    /// Target: signs the receipt for this session.
    pub fn sign_receipt(&self, receipt: &Receipt) -> Result<Vec<u8>, Error> {
        self.require(Role::Target)?;
        receipt.validate()?;
        self.sign(RECEIPT_CONTEXT, receipt.encode())
    }

    /// Source: checks the target's signature and that the receipt answers
    /// `signed_manifest`, the manifest this side sent.
    pub fn verify_receipt(&self, signed: &[u8], signed_manifest: &[u8]) -> Result<Receipt, Error> {
        self.require(Role::Source)?;
        let receipt = Receipt::decode(self.verify(RECEIPT_CONTEXT, signed)?)?;
        receipt.validate()?;
        if !bool::from(receipt.manifest_hash.ct_eq(&manifest_hash(signed_manifest))) {
            return Err(Error::ReceiptMismatch);
        }
        Ok(receipt)
    }
}
