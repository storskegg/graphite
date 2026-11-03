//! Pairing: two sans-I/O state machines, one per role, over the USB cable,
//! checked by a six-digit comparison.
//!
//! ```text
//!   target                                     source
//!   Hello {version, suite, session}       ->
//!   <-  SourceCommit {session, H(share_S)}
//!   TargetShare {share_T}                 ->
//!   <-  SourceShare {share_S, enc_S}
//!   TargetKem {enc_T, confirm_T}          ->
//!   <-  SourceConfirm {confirm_S}
//! ```
//!
//! A key share is an ephemeral X-Wing public key, an ephemeral ML-DSA-65
//! verification key and a 32-byte nonce. `H` is a commitment that binds the
//! role and session. The source commits before it sees the target's share and
//! reveals after, so neither side, nor anyone between them, can choose a
//! share to steer the code. Both screens show six digits from the transcript
//! hash. The code is only compared by the user: it never feeds a key, so
//! there is nothing to search offline. Pairing cannot finish until the user
//! has confirmed it on both phones.
//!
//! Each side then encapsulates to the other's X-Wing key with HPKE (base
//! mode), so each side's keys depend on a secret only the genuine peer can
//! recover: anyone can encapsulate to a public key, but only its owner can
//! decapsulate.
//!
//! The transcript binds the protocol version, the cipher suite, the session
//! ID, both shares and both encapsulations. Key confirmation in both
//! directions proves the two transcripts and secrets are the same.

use blake3::Hasher;
use hpke_rs::HpkePrivateKey;
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

use crate::codec::{Reader, Writer};
use crate::hpke::{self, SUITE, XWING_ENC_LEN, XWING_PUBLIC_KEY_LEN};
use crate::random::random;
use crate::session::Session;
use crate::signing::{SigningKey, VERIFICATION_KEY_LEN};
use crate::{Error, PROTOCOL_VERSION, Role, SUITE_ID, SUITE_NAME};

const HELLO: u8 = 1;
const SOURCE_COMMIT: u8 = 2;
const TARGET_SHARE: u8 = 3;
const SOURCE_SHARE: u8 = 4;
const TARGET_KEM: u8 = 5;
const SOURCE_CONFIRM: u8 = 6;

const SESSION_ID_LEN: usize = 16;
const SHARE_LEN: usize = XWING_PUBLIC_KEY_LEN + VERIFICATION_KEY_LEN + 32;

/// Opens a message: checks its tag and hands back a reader for the rest.
fn open(msg: &[u8], expected: u8) -> Result<Reader<'_>, Error> {
    let mut r = Reader::new(msg);
    match r.u8()? {
        t if t == expected => Ok(r),
        HELLO..=SOURCE_CONFIRM => Err(Error::UnexpectedMessage),
        _ => Err(Error::Malformed),
    }
}

fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    a.ct_eq(b).into()
}

#[derive(Clone)]
struct Transcript(Hasher);

impl Transcript {
    fn new() -> Self {
        let mut t = Transcript(Hasher::new_derive_key("graphite/1 pairing transcript"));
        t.absorb(&PROTOCOL_VERSION.to_be_bytes());
        t.absorb(SUITE_NAME);
        t
    }
    fn absorb(&mut self, item: &[u8]) {
        self.0.update(&(item.len() as u64).to_be_bytes());
        self.0.update(item);
    }
    fn hash(&self) -> [u8; 32] {
        *self.0.finalize().as_bytes()
    }
}

fn commitment(role: Role, session_id: &[u8; SESSION_ID_LEN], share: &[u8]) -> [u8; 32] {
    let mut h = Hasher::new_derive_key("graphite/1 key share commitment");
    h.update(&[role as u8]);
    h.update(session_id);
    h.update(share);
    *h.finalize().as_bytes()
}

fn hpke_info(sender: Role, th1: &[u8; 32]) -> Vec<u8> {
    let label: &[u8] = match sender {
        Role::Source => b"graphite/1 hpke source to target",
        Role::Target => b"graphite/1 hpke target to source",
    };
    [label, th1].concat()
}

const EXPORTER_CONTEXT: &[u8] = b"graphite/1 session secret";

fn six_digit_code(th1: &[u8; 32]) -> String {
    let d = blake3::derive_key("graphite/1 six-digit code", th1);
    let n = u64::from_be_bytes(d[..8].try_into().expect("8 bytes")) % 1_000_000;
    format!("{n:06}")
}

/// This side's ephemeral keys. The encoded share is what the peer sees.
struct OwnShare {
    xwing: HpkePrivateKey,
    signing: Option<SigningKey>,
    encoded: Vec<u8>,
}

impl OwnShare {
    fn generate() -> Result<Self, Error> {
        let (xwing, xwing_pk) = hpke::generate_key_pair(SUITE)?;
        let (signing, verification_key) = SigningKey::generate()?;
        let nonce = random::<32>()?;
        let encoded = [&xwing_pk[..], &verification_key[..], &nonce].concat();
        debug_assert_eq!(encoded.len(), SHARE_LEN);
        Ok(Self {
            xwing,
            signing: Some(signing),
            encoded,
        })
    }
}

/// The parts of the peer's share this side uses.
struct PeerShare {
    xwing_pk: Vec<u8>,
    verification_key: Box<[u8; VERIFICATION_KEY_LEN]>,
}

impl PeerShare {
    fn parse(share: &[u8]) -> Self {
        let (xwing_pk, rest) = share.split_at(XWING_PUBLIC_KEY_LEN);
        Self {
            xwing_pk: xwing_pk.to_vec(),
            verification_key: Box::new(rest[..VERIFICATION_KEY_LEN].try_into().expect("length")),
        }
    }
}

/// Everything derived once both encapsulations are in the transcript.
pub(crate) struct Keys {
    pub transcript_hash: [u8; 32],
    pub source_to_target: Zeroizing<[u8; 32]>,
    pub target_to_source: Zeroizing<[u8; 32]>,
    confirm_source: Zeroizing<[u8; 32]>,
    confirm_target: Zeroizing<[u8; 32]>,
}

impl Keys {
    fn derive(th2: [u8; 32], from_source: &[u8; 32], from_target: &[u8; 32]) -> Self {
        let mut ikm = Zeroizing::new([0u8; 96]);
        ikm[..32].copy_from_slice(&th2);
        ikm[32..64].copy_from_slice(from_source);
        ikm[64..].copy_from_slice(from_target);
        let master = Zeroizing::new(blake3::derive_key("graphite/1 session master key", &*ikm));
        let key = |context| Zeroizing::new(blake3::derive_key(context, &*master));
        Keys {
            transcript_hash: th2,
            source_to_target: key("graphite/1 stream key source to target"),
            target_to_source: key("graphite/1 stream key target to source"),
            confirm_source: key("graphite/1 key confirmation source"),
            confirm_target: key("graphite/1 key confirmation target"),
        }
    }

    fn confirmation(&self, from: Role) -> [u8; 32] {
        let key = match from {
            Role::Source => &self.confirm_source,
            Role::Target => &self.confirm_target,
        };
        *blake3::keyed_hash(key, &self.transcript_hash).as_bytes()
    }
}

/// State shared by both roles.
struct Common {
    share: OwnShare,
    transcript: Transcript,
    code: Option<String>,
    code_confirmed: bool,
}

impl Common {
    /// Absorbs the source's share, which completes the part of the
    /// transcript that the code and the HPKE info cover.
    fn shares_done(&mut self, source_share: &[u8]) -> [u8; 32] {
        self.transcript.absorb(source_share);
        let th1 = self.transcript.hash();
        self.code = Some(six_digit_code(&th1));
        th1
    }

    fn confirm_code(&mut self) -> Result<(), Error> {
        if self.code.is_none() {
            return Err(Error::UnexpectedMessage);
        }
        self.code_confirmed = true;
        Ok(())
    }
}

enum TargetState {
    AwaitCommit,
    AwaitSourceShare { commitment: [u8; 32] },
    AwaitConfirm { keys: Keys, peer: PeerShare },
    Done { keys: Keys, peer: PeerShare },
    Finished,
    Failed,
}

/// The new phone's side of pairing.
pub struct TargetPairing {
    common: Common,
    session_id: [u8; SESSION_ID_LEN],
    hello: Vec<u8>,
    state: TargetState,
}

impl TargetPairing {
    pub fn new() -> Result<Self, Error> {
        let share = OwnShare::generate()?;
        let session_id = random::<SESSION_ID_LEN>()?;
        let hello = Writer::new()
            .u8(HELLO)
            .u16(PROTOCOL_VERSION)
            .u16(SUITE_ID)
            .fixed(&session_id)
            .finish();
        let mut transcript = Transcript::new();
        transcript.absorb(&hello);
        Ok(Self {
            common: Common {
                share,
                transcript,
                code: None,
                code_confirmed: false,
            },
            session_id,
            hello,
            state: TargetState::AwaitCommit,
        })
    }

    /// The first message on the cable. The same bytes on every call.
    pub fn hello(&self) -> Vec<u8> {
        self.hello.clone()
    }

    /// Takes the source's next message and returns the reply, if any. Any
    /// error ends the pairing.
    pub fn handle(&mut self, msg: &[u8]) -> Result<Option<Vec<u8>>, Error> {
        if matches!(self.state, TargetState::Failed) {
            return Err(Error::Failed);
        }
        let result = self.step(msg);
        if result.is_err() {
            self.state = TargetState::Failed;
        }
        result
    }

    fn step(&mut self, msg: &[u8]) -> Result<Option<Vec<u8>>, Error> {
        match core::mem::replace(&mut self.state, TargetState::Failed) {
            TargetState::AwaitCommit => {
                let mut r = open(msg, SOURCE_COMMIT)?;
                let session_id = r.array::<SESSION_ID_LEN>()?;
                let commitment = r.array::<32>()?;
                r.end()?;
                if session_id != self.session_id {
                    return Err(Error::SessionMismatch);
                }
                self.common.transcript.absorb(msg);
                let reply = Writer::new()
                    .u8(TARGET_SHARE)
                    .fixed(&self.common.share.encoded)
                    .finish();
                self.common.transcript.absorb(&reply);
                self.state = TargetState::AwaitSourceShare { commitment };
                Ok(Some(reply))
            }
            TargetState::AwaitSourceShare {
                commitment: expected,
            } => {
                let mut r = open(msg, SOURCE_SHARE)?;
                let share = r.take(SHARE_LEN)?;
                let enc_source = r.take(XWING_ENC_LEN)?;
                r.end()?;
                if !ct_eq(
                    &commitment(Role::Source, &self.session_id, share),
                    &expected,
                ) {
                    return Err(Error::CommitmentMismatch);
                }
                let peer = PeerShare::parse(share);
                let th1 = self.common.shares_done(share);

                let ctx = hpke::setup_receiver(
                    SUITE,
                    enc_source,
                    &self.common.share.xwing,
                    &hpke_info(Role::Source, &th1),
                )?;
                let from_source = hpke::export(&ctx, EXPORTER_CONTEXT)?;
                self.common.transcript.absorb(enc_source);

                let (enc_target, ctx) = hpke::setup_sender(
                    SUITE,
                    hpke::system_rng()?,
                    &peer.xwing_pk,
                    &hpke_info(Role::Target, &th1),
                )?;
                let from_target = hpke::export(&ctx, EXPORTER_CONTEXT)?;
                self.common.transcript.absorb(&enc_target);

                let keys = Keys::derive(self.common.transcript.hash(), &from_source, &from_target);
                let reply = Writer::new()
                    .u8(TARGET_KEM)
                    .fixed(&enc_target)
                    .fixed(&keys.confirmation(Role::Target))
                    .finish();
                self.state = TargetState::AwaitConfirm { keys, peer };
                Ok(Some(reply))
            }
            TargetState::AwaitConfirm { keys, peer } => {
                let mut r = open(msg, SOURCE_CONFIRM)?;
                let tag = r.array::<32>()?;
                r.end()?;
                if !ct_eq(&tag, &keys.confirmation(Role::Source)) {
                    return Err(Error::KeyConfirmationFailed);
                }
                self.state = TargetState::Done { keys, peer };
                Ok(None)
            }
            TargetState::Done { .. } | TargetState::Finished => Err(Error::UnexpectedMessage),
            TargetState::Failed => Err(Error::Failed),
        }
    }

    /// Once both shares are known: the six digits to show.
    pub fn code(&self) -> Option<String> {
        self.common.code.clone()
    }

    /// The user saw the same code on both phones.
    pub fn confirm_code(&mut self) -> Result<(), Error> {
        self.common.confirm_code()
    }

    /// The paired session, once the handshake is complete and the code
    /// confirmed. Can be taken once.
    pub fn finish(&mut self) -> Result<Session, Error> {
        finish(&mut self.state, &mut self.common, Role::Target)
    }
}

/// The two roles' states, as far as finishing goes.
trait Finishable {
    fn is_failed(&self) -> bool;
    fn is_done(&self) -> bool;
    /// Takes the keys out of a done state, leaving it finished.
    fn take_done(&mut self) -> Option<(Keys, PeerShare)>;
}

macro_rules! finishable {
    ($state:ident) => {
        impl Finishable for $state {
            fn is_failed(&self) -> bool {
                matches!(self, $state::Failed)
            }
            fn is_done(&self) -> bool {
                matches!(self, $state::Done { .. })
            }
            fn take_done(&mut self) -> Option<(Keys, PeerShare)> {
                if !self.is_done() {
                    return None;
                }
                match core::mem::replace(self, $state::Finished) {
                    $state::Done { keys, peer } => Some((keys, peer)),
                    _ => unreachable!(),
                }
            }
        }
    };
}
finishable!(TargetState);
finishable!(SourceState);

fn finish(state: &mut impl Finishable, common: &mut Common, role: Role) -> Result<Session, Error> {
    if state.is_failed() {
        return Err(Error::Failed);
    }
    if state.is_done() && !common.code_confirmed {
        return Err(Error::CodeNotConfirmed);
    }
    let (keys, peer) = state.take_done().ok_or(Error::UnexpectedMessage)?;
    // The X-Wing key has done its job; wipe it now rather than when the
    // pairing object goes away.
    drop(core::mem::take(&mut common.share.xwing));
    let signing = common.share.signing.take().ok_or(Error::Failed)?;
    Ok(Session::new(role, keys, signing, peer.verification_key))
}

enum SourceState {
    AwaitHello,
    AwaitTargetShare,
    AwaitTargetKem {
        th1: [u8; 32],
        from_source: Zeroizing<[u8; 32]>,
        peer: PeerShare,
    },
    Done {
        keys: Keys,
        peer: PeerShare,
    },
    Finished,
    Failed,
}

/// The old phone's side of pairing.
pub struct SourcePairing {
    common: Common,
    state: SourceState,
}

impl SourcePairing {
    pub fn new() -> Result<Self, Error> {
        Ok(Self {
            common: Common {
                share: OwnShare::generate()?,
                transcript: Transcript::new(),
                code: None,
                code_confirmed: false,
            },
            state: SourceState::AwaitHello,
        })
    }

    /// Takes the target's next message (the hello first) and returns the
    /// reply. Any error ends the pairing.
    pub fn handle(&mut self, msg: &[u8]) -> Result<Option<Vec<u8>>, Error> {
        if matches!(self.state, SourceState::Failed) {
            return Err(Error::Failed);
        }
        let result = self.step(msg);
        if result.is_err() {
            self.state = SourceState::Failed;
        }
        result
    }

    fn step(&mut self, msg: &[u8]) -> Result<Option<Vec<u8>>, Error> {
        match core::mem::replace(&mut self.state, SourceState::Failed) {
            SourceState::AwaitHello => {
                let mut r = open(msg, HELLO)?;
                if r.u16()? != PROTOCOL_VERSION {
                    return Err(Error::UnsupportedVersion);
                }
                if r.u16()? != SUITE_ID {
                    return Err(Error::UnsupportedSuite);
                }
                let session_id = r.array::<SESSION_ID_LEN>()?;
                r.end()?;
                self.common.transcript.absorb(msg);

                let reply = Writer::new()
                    .u8(SOURCE_COMMIT)
                    .fixed(&session_id)
                    .fixed(&commitment(
                        Role::Source,
                        &session_id,
                        &self.common.share.encoded,
                    ))
                    .finish();
                self.common.transcript.absorb(&reply);
                self.state = SourceState::AwaitTargetShare;
                Ok(Some(reply))
            }
            SourceState::AwaitTargetShare => {
                let mut r = open(msg, TARGET_SHARE)?;
                let share = r.take(SHARE_LEN)?;
                r.end()?;
                let peer = PeerShare::parse(share);
                self.common.transcript.absorb(msg);
                let own_share = self.common.share.encoded.clone();
                let th1 = self.common.shares_done(&own_share);

                let (enc_source, ctx) = hpke::setup_sender(
                    SUITE,
                    hpke::system_rng()?,
                    &peer.xwing_pk,
                    &hpke_info(Role::Source, &th1),
                )?;
                let from_source = hpke::export(&ctx, EXPORTER_CONTEXT)?;
                self.common.transcript.absorb(&enc_source);

                let reply = Writer::new()
                    .u8(SOURCE_SHARE)
                    .fixed(&own_share)
                    .fixed(&enc_source)
                    .finish();
                self.state = SourceState::AwaitTargetKem {
                    th1,
                    from_source,
                    peer,
                };
                Ok(Some(reply))
            }
            SourceState::AwaitTargetKem {
                th1,
                from_source,
                peer,
            } => {
                let mut r = open(msg, TARGET_KEM)?;
                let enc_target = r.take(XWING_ENC_LEN)?;
                let tag = r.array::<32>()?;
                r.end()?;
                let ctx = hpke::setup_receiver(
                    SUITE,
                    enc_target,
                    &self.common.share.xwing,
                    &hpke_info(Role::Target, &th1),
                )?;
                let from_target = hpke::export(&ctx, EXPORTER_CONTEXT)?;
                self.common.transcript.absorb(enc_target);

                let keys = Keys::derive(self.common.transcript.hash(), &from_source, &from_target);
                if !ct_eq(&tag, &keys.confirmation(Role::Target)) {
                    return Err(Error::KeyConfirmationFailed);
                }
                let reply = Writer::new()
                    .u8(SOURCE_CONFIRM)
                    .fixed(&keys.confirmation(Role::Source))
                    .finish();
                self.state = SourceState::Done { keys, peer };
                Ok(Some(reply))
            }
            SourceState::Done { .. } | SourceState::Finished => Err(Error::UnexpectedMessage),
            SourceState::Failed => Err(Error::Failed),
        }
    }

    /// Once both shares are known: the six digits to show.
    pub fn code(&self) -> Option<String> {
        self.common.code.clone()
    }

    /// The user saw the same code on both phones.
    pub fn confirm_code(&mut self) -> Result<(), Error> {
        self.common.confirm_code()
    }

    /// The paired session, once the handshake is complete and the code
    /// confirmed. Can be taken once.
    pub fn finish(&mut self) -> Result<Session, Error> {
        finish(&mut self.state, &mut self.common, Role::Source)
    }
}
