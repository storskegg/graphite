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

/// The identity of a message, call, contact or blocked number (docs/records.md):
/// BLAKE3 in key-derivation mode with the context `graphite/1 record identity`,
/// over the kind and each normalized field with its length, so no two field
/// lists give the same input. Both phones compute it the same way.
pub fn record_identity(kind: crate::RecordKind, fields: &[&[u8]]) -> [u8; 32] {
    let mut h = blake3::Hasher::new_derive_key("graphite/1 record identity");
    h.update(&[kind as u8]);
    for f in fields {
        h.update(&(f.len() as u64).to_le_bytes());
        h.update(f);
    }
    *h.finalize().as_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RecordKind;

    #[test]
    fn record_identity_separates_kinds_and_fields() {
        let a = record_identity(RecordKind::Sms, &[b"1", b"+15550100", b"hi"]);
        assert_eq!(
            a,
            record_identity(RecordKind::Sms, &[b"1", b"+15550100", b"hi"])
        );
        assert_ne!(
            a,
            record_identity(RecordKind::Call, &[b"1", b"+15550100", b"hi"])
        );
        // Moving a byte from one field to the next changes it.
        assert_ne!(
            a,
            record_identity(RecordKind::Sms, &[b"1+", b"15550100", b"hi"])
        );
        assert_ne!(
            a,
            record_identity(RecordKind::Sms, &[b"1", b"+15550100", b"hi", b""])
        );
        // Not a plain hash of the concatenation.
        assert_ne!(a, FileHasher::hash(b"1+15550100hi"));
    }
}
