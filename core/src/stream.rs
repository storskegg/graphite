//! Chunked XChaCha20-Poly1305, one stream per direction per session.
//!
//! Each chunk is `flag || ciphertext || tag`, at most [`CHUNK_SIZE`] bytes of
//! plaintext. The 24-byte nonce is built, never sent:
//!
//! ```text
//! "g" | direction | 13 zero bytes | counter (u64, big-endian) | last flag
//! ```
//!
//! The counter makes reordering and replay fail; the flag, carried in the
//! clear but bound through the nonce, makes truncation detectable; the
//! direction byte, on top of the key being per direction, makes a chunk sent
//! one way fail the other way.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use zeroize::Zeroizing;

use crate::{Error, Role};

/// Largest plaintext in one chunk: 1 MiB.
pub const CHUNK_SIZE: usize = 1 << 20;
/// Bytes a sealed chunk adds to its plaintext: the flag byte and the tag.
pub const CHUNK_OVERHEAD: usize = 1 + 16;

const AAD: &[u8] = b"graphite/1 stream";

/// A chunk that authenticated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Chunk {
    pub data: Vec<u8>,
    /// Set on the final chunk of the stream.
    pub last: bool,
}

fn nonce(sender: Role, counter: u64, last: bool) -> XNonce {
    let mut n = [0u8; 24];
    n[0] = b'g';
    n[1] = sender as u8;
    n[15..23].copy_from_slice(&counter.to_be_bytes());
    n[23] = last as u8;
    XNonce::from(n)
}

pub(crate) struct StreamSealer {
    cipher: XChaCha20Poly1305,
    sender: Role,
    counter: u64,
    finished: bool,
}

impl StreamSealer {
    pub fn new(key: &Zeroizing<[u8; 32]>, sender: Role) -> Self {
        Self {
            cipher: XChaCha20Poly1305::new(&(**key).into()),
            sender,
            counter: 0,
            finished: false,
        }
    }

    pub fn seal(&mut self, plaintext: &[u8], last: bool) -> Result<Vec<u8>, Error> {
        if self.finished {
            return Err(Error::StreamFinished);
        }
        if plaintext.len() > CHUNK_SIZE {
            return Err(Error::ChunkTooLarge);
        }
        if self.counter == u64::MAX {
            return Err(Error::StreamExhausted);
        }
        let nonce = nonce(self.sender, self.counter, last);
        let sealed = self
            .cipher
            .encrypt(
                &nonce,
                Payload {
                    msg: plaintext,
                    aad: AAD,
                },
            )
            .map_err(|_| Error::Crypto)?;
        self.counter += 1;
        self.finished = last;
        let mut out = Vec::with_capacity(1 + sealed.len());
        out.push(last as u8);
        out.extend_from_slice(&sealed);
        Ok(out)
    }
}

pub(crate) struct StreamOpener {
    cipher: XChaCha20Poly1305,
    sender: Role,
    counter: u64,
    finished: bool,
    failed: bool,
}

impl StreamOpener {
    pub fn new(key: &Zeroizing<[u8; 32]>, sender: Role) -> Self {
        Self {
            cipher: XChaCha20Poly1305::new(&(**key).into()),
            sender,
            counter: 0,
            finished: false,
            failed: false,
        }
    }

    pub fn open(&mut self, chunk: &[u8]) -> Result<Chunk, Error> {
        if self.failed {
            return Err(Error::Failed);
        }
        let result = self.open_inner(chunk);
        // Running past the end is reported but not fatal; anything else is.
        if matches!(result, Err(e) if e != Error::StreamFinished) {
            self.failed = true;
        }
        result
    }

    fn open_inner(&mut self, chunk: &[u8]) -> Result<Chunk, Error> {
        if self.finished {
            return Err(Error::StreamFinished);
        }
        if chunk.len() > CHUNK_SIZE + CHUNK_OVERHEAD {
            return Err(Error::ChunkTooLarge);
        }
        if chunk.len() < CHUNK_OVERHEAD {
            return Err(Error::Malformed);
        }
        let last = match chunk[0] {
            0 => false,
            1 => true,
            _ => return Err(Error::Malformed),
        };
        if self.counter == u64::MAX {
            return Err(Error::StreamExhausted);
        }
        let nonce = nonce(self.sender, self.counter, last);
        let data = self
            .cipher
            .decrypt(
                &nonce,
                Payload {
                    msg: &chunk[1..],
                    aad: AAD,
                },
            )
            .map_err(|_| Error::StreamAuthentication)?;
        self.counter += 1;
        self.finished = last;
        Ok(Chunk { data, last })
    }

    /// Call at the end of input: fails unless the last chunk arrived.
    pub fn finish(&self) -> Result<(), Error> {
        if self.failed {
            Err(Error::Failed)
        } else if self.finished {
            Ok(())
        } else {
            Err(Error::StreamTruncated)
        }
    }
}
