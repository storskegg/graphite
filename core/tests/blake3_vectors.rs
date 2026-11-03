//! Official BLAKE3 test vectors (BLAKE3-team/BLAKE3, test_vectors/test_vectors.json),
//! fed through [`FileHasher`] in uneven pieces, as files arrive.

use graphite_core::FileHasher;
use serde_json::Value;

fn hex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn official_vectors_in_any_split() {
    let vectors: Value = serde_json::from_str(include_str!("vectors/blake3.json")).unwrap();
    let cases = vectors["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 35);
    for case in cases {
        let len = case["input_len"].as_u64().unwrap() as usize;
        let input: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
        let expected = &hex(case["hash"].as_str().unwrap())[..32];

        assert_eq!(FileHasher::hash(&input), expected, "len {len}");
        for piece in [1, 63, 1024, 1025, 65536] {
            let mut hasher = FileHasher::new();
            for part in input.chunks(piece) {
                hasher.update(part);
            }
            assert_eq!(hasher.finalize(), expected, "len {len}, piece {piece}");
        }
    }
}
