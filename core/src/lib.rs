//! graphite's crypto core.
//!
//! Small, synchronous and free of I/O: the app moves the bytes, this crate
//! decides what they are. See `docs/design.md` (Pairing, Cryptography) and
//! `docs/media.md` (the manifest).
//!
//! - [`TargetPairing`] / [`SourcePairing`]: pairing by NFC, QR or six-digit code,
//!   ending in a [`Session`].
//! - [`Session`]: chunked XChaCha20-Poly1305 each way, ML-DSA-65 signed
//!   [`Manifest`] (source), [`Plan`] and [`Receipt`] (target).
//! - [`FileHasher`]: streaming BLAKE3 of file contents.

mod codec;
mod document;
mod error;
mod hash;
mod hpke;
mod pairing;
mod random;
mod session;
mod signing;
mod stream;

pub use document::{
    FileDigest, Manifest, ManifestEntry, MetadataChoice, NotCopiedReason, Plan, PlanEntry,
    PlanStatus, PlanTotals, Receipt, ReceiptEntry, ReceiptStatus, plan_totals,
};
pub use error::Error;
pub use hash::FileHasher;
pub use pairing::{Method, SourcePairing, TargetPairing};
pub use session::{Session, manifest_hash, plan_hash};
pub use stream::{CHUNK_OVERHEAD, CHUNK_SIZE, Chunk};

/// The protocol version, sent in the hello and bound into the transcript.
pub const PROTOCOL_VERSION: u16 = 1;

/// The cipher suite's wire identifier, sent in the hello.
pub const SUITE_ID: u16 = 1;

/// What suite 1 is, bound into the transcript.
pub const SUITE_NAME: &[u8] = b"graphite suite 1: \
    HPKE RFC 9180 (KEM X-Wing 0x647a, KDF HKDF-SHA256 0x0001, AEAD ChaCha20-Poly1305 0x0003); \
    ML-DSA-65; XChaCha20-Poly1305 in 1 MiB chunks; BLAKE3";

/// Which phone this is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Role {
    /// The old phone: sends the data, signs the manifest.
    Source = 1,
    /// The new phone: USB host, receives the data, signs the receipt.
    Target = 2,
}

impl Role {
    pub(crate) fn peer(self) -> Role {
        match self {
            Role::Source => Role::Target,
            Role::Target => Role::Source,
        }
    }
}
