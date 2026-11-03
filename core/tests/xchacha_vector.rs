//! The AEAD_XCHACHA20_POLY1305 test vector from draft-irtf-cfrg-xchacha-03,
//! Appendix A.3.1, against the RustCrypto implementation the stream uses.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};

fn hex(s: &str) -> Vec<u8> {
    let s: String = s.split_whitespace().collect();
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn draft_irtf_cfrg_xchacha_a_3_1() {
    let plaintext = hex(
        "4c616469657320616e642047656e746c656d656e206f662074686520636c6173
        73206f66202739393a204966204920636f756c64206f6666657220796f75206f
        6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73
        637265656e20776f756c642062652069742e",
    );
    let aad = hex("50515253c0c1c2c3c4c5c6c7");
    let key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
    let nonce = hex("404142434445464748494a4b4c4d4e4f5051525354555657");
    let expected = hex(
        "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb
        731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452
        2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9
        21f9664c97637da9768812f615c68b13b52e
        c0875924c1c7987947deafd8780acf49",
    );

    let cipher = XChaCha20Poly1305::new_from_slice(&key).unwrap();
    let nonce = XNonce::try_from(&nonce[..]).unwrap();
    let sealed = cipher
        .encrypt(
            &nonce,
            Payload {
                msg: &plaintext,
                aad: &aad,
            },
        )
        .unwrap();
    assert_eq!(sealed, expected);
    let opened = cipher
        .decrypt(
            &nonce,
            Payload {
                msg: &sealed,
                aad: &aad,
            },
        )
        .unwrap();
    assert_eq!(opened, plaintext);
}
