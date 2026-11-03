use core::fmt;

/// Everything that can go wrong. Errors carry no detail an attacker could
/// use; every failure of a pairing or stream is final for that object.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum Error {
    /// A message or document could not be decoded.
    Malformed,
    /// The message is valid but not expected in the current state.
    UnexpectedMessage,
    /// The peer speaks another protocol version.
    UnsupportedVersion,
    /// The peer uses another cipher suite.
    UnsupportedSuite,
    /// The peer started a different pairing method, or the call does not apply to this method.
    WrongMethod,
    /// The message belongs to another pairing session.
    SessionMismatch,
    /// A key share does not match the hash committed to earlier.
    CommitmentMismatch,
    /// The peer derived different keys: a man-in-the-middle, a wrong QR secret, or tampering.
    KeyConfirmationFailed,
    /// The six-digit code has not been confirmed by the user.
    CodeNotConfirmed,
    /// This object failed earlier and refuses to continue.
    Failed,
    /// A KEM or HPKE operation failed.
    Crypto,
    /// The system random number generator failed.
    Randomness,
    /// A chunk failed to authenticate: tampered, reordered, replayed or from the other direction.
    StreamAuthentication,
    /// The stream ended without its last chunk.
    StreamTruncated,
    /// Data was sent or received after the last chunk.
    StreamFinished,
    /// A chunk is larger than [`crate::CHUNK_SIZE`].
    ChunkTooLarge,
    /// The chunk counter is exhausted.
    StreamExhausted,
    /// A signature does not verify.
    SignatureInvalid,
    /// This side's role does not sign or verify that document.
    WrongRole,
    /// A manifest breaks a rule: an unsafe path, or sent bytes differing from the original while keeping metadata.
    InvalidManifest,
    /// A plan does not name every manifest entry exactly once, or its totals do not add up.
    InvalidPlan,
    /// A receipt does not name every manifest entry exactly once.
    InvalidReceipt,
    /// The plan or receipt answers a different manifest.
    ManifestMismatch,
    /// The receipt follows a different plan.
    PlanMismatch,
    /// The target requested an entry the plan does not have it copy.
    NotInPlan,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for Error {}
