/// BLAKE3 of a file's exact bytes, fed in pieces as they are read.
///
/// This is plain, unkeyed BLAKE3: the same bytes give the same hash on both
/// phones and across sessions, which is what duplicate detection needs.
#[derive(Clone, Default)]
pub struct FileHasher(blake3::Hasher);

impl FileHasher {
    pub fn new() -> Self {
        Self(blake3::Hasher::new())
    }

    pub fn update(&mut self, bytes: &[u8]) {
        self.0.update(bytes);
    }

    pub fn finalize(&self) -> [u8; 32] {
        *self.0.finalize().as_bytes()
    }

    /// The hash of `bytes` in one call.
    pub fn hash(bytes: &[u8]) -> [u8; 32] {
        *blake3::hash(bytes).as_bytes()
    }
}
