//! HPKE (RFC 9180) with the X-Wing KEM, as provided by hpke-rs on libcrux.
//!
//! graphite only uses HPKE's key schedule and secret export: the bulk data
//! runs over [`crate::stream`]. The suite is fixed; the functions take a
//! [`Suite`] only so that the RFC 9180 test vectors, which do not cover
//! X-Wing, can run through the same code.

use hpke_rs::hpke_types::{AeadAlgorithm, KdfAlgorithm, KemAlgorithm};
use hpke_rs::libcrux::HpkeLibcrux;
use hpke_rs::{Context, Hpke, HpkePrivateKey, HpkePublicKey, Mode};
use hpke_rs_crypto::{HpkeDefaultPrng, TryCryptoRng};
use zeroize::Zeroizing;

use crate::Error;

#[derive(Clone, Copy, Debug)]
pub(crate) struct Suite {
    pub kem: KemAlgorithm,
    pub kdf: KdfAlgorithm,
    pub aead: AeadAlgorithm,
}

/// X-Wing (codepoint 0x647a), HKDF-SHA256, ChaCha20-Poly1305.
pub(crate) const SUITE: Suite = Suite {
    kem: KemAlgorithm::XWingDraft06,
    kdf: KdfAlgorithm::HkdfSha256,
    aead: AeadAlgorithm::ChaCha20Poly1305,
};

pub(crate) const XWING_PUBLIC_KEY_LEN: usize = 1216;
pub(crate) const XWING_ENC_LEN: usize = 1120;

/// A PSK and its identifier, both non-empty (RFC 9180, section 5.1.2).
pub(crate) struct Psk<'a> {
    pub psk: &'a [u8],
    pub id: &'a [u8],
}

fn mode(psk: &Option<Psk>) -> Mode {
    if psk.is_some() { Mode::Psk } else { Mode::Base }
}

fn psk_parts<'a>(psk: &'a Option<Psk>) -> (Option<&'a [u8]>, Option<&'a [u8]>) {
    match psk {
        Some(p) => (Some(p.psk), Some(p.id)),
        None => (None, None),
    }
}

pub(crate) fn generate_key_pair(suite: Suite) -> Result<(HpkePrivateKey, Vec<u8>), Error> {
    let mut hpke = Hpke::<HpkeLibcrux>::try_new(Mode::Base, suite.kem, suite.kdf, suite.aead)
        .map_err(|_| Error::Randomness)?;
    let (sk, pk) = hpke
        .generate_key_pair()
        .map_err(|_| Error::Crypto)?
        .into_keys();
    Ok((sk, pk.as_slice().to_vec()))
}

/// Sets up a sender context to `pk_r`, drawing the encapsulation randomness
/// from `rng`. Returns the encapsulated key and the context.
pub(crate) fn setup_sender<R: TryCryptoRng + 'static>(
    suite: Suite,
    rng: R,
    pk_r: &[u8],
    info: &[u8],
    psk: Option<Psk>,
) -> Result<(Vec<u8>, Context<HpkeLibcrux<R>>), Error> {
    let mut hpke =
        Hpke::<HpkeLibcrux<R>>::new_with_rng(mode(&psk), suite.kem, suite.kdf, suite.aead, rng);
    let (p, id) = psk_parts(&psk);
    hpke.setup_sender(&HpkePublicKey::new(pk_r.to_vec()), info, p, id, None)
        .map_err(|_| Error::Crypto)
}

pub(crate) fn setup_receiver(
    suite: Suite,
    enc: &[u8],
    sk_r: &HpkePrivateKey,
    info: &[u8],
    psk: Option<Psk>,
) -> Result<Context<HpkeLibcrux>, Error> {
    // The receiver draws no randomness; the PRNG is only there because hpke-rs wants one.
    let prng = HpkeLibcrux::try_prng().map_err(|_| Error::Randomness)?;
    let hpke =
        Hpke::<HpkeLibcrux>::new_with_rng(mode(&psk), suite.kem, suite.kdf, suite.aead, prng);
    let (p, id) = psk_parts(&psk);
    hpke.setup_receiver(enc, sk_r, info, p, id, None)
        .map_err(|_| Error::Crypto)
}

pub(crate) fn export<C: hpke_rs_crypto::HpkeCrypto>(
    ctx: &Context<C>,
    exporter_context: &[u8],
) -> Result<Zeroizing<[u8; 32]>, Error> {
    let out = Zeroizing::new(
        ctx.export(exporter_context, 32)
            .map_err(|_| Error::Crypto)?,
    );
    let mut key = Zeroizing::new([0u8; 32]);
    key.copy_from_slice(&out);
    Ok(key)
}

/// The system RNG, for encapsulation.
pub(crate) fn system_rng() -> Result<hpke_rs::libcrux::HpkeLibcruxPrng, Error> {
    HpkeLibcrux::try_prng().map_err(|_| Error::Randomness)
}

#[cfg(test)]
mod tests {
    //! RFC 9180 (cfrg/draft-irtf-cfrg-hpke test-vectors.json, filtered to
    //! DHKEM(X25519) in base and PSK modes), draft-ietf-hpke-pq (hpkewg/hpke-pq
    //! test-vectors.json, the X-Wing suites) and the X-Wing draft's own KEM
    //! vectors (draft-connolly-cfrg-xwing-kem spec/test-vectors.json).

    use super::*;
    use core::convert::Infallible;
    use hpke_rs_crypto::{HpkeCrypto, TryRng};
    use serde_json::Value;

    fn hex(v: &Value) -> Vec<u8> {
        let s = v.as_str().unwrap();
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    /// Hands out fixed bytes, so encapsulation reproduces a vector's `ikmE`.
    struct FixedRng(Vec<u8>);

    impl TryRng for FixedRng {
        type Error = Infallible;
        fn try_next_u32(&mut self) -> Result<u32, Infallible> {
            unreachable!()
        }
        fn try_next_u64(&mut self) -> Result<u64, Infallible> {
            unreachable!()
        }
        fn try_fill_bytes(&mut self, dst: &mut [u8]) -> Result<(), Infallible> {
            assert!(dst.len() <= self.0.len(), "vector ran out of randomness");
            let rest = self.0.split_off(dst.len());
            dst.copy_from_slice(&self.0);
            self.0 = rest;
            Ok(())
        }
    }
    impl TryCryptoRng for FixedRng {}

    fn suite_of(v: &Value) -> Option<Suite> {
        let kem = KemAlgorithm::try_from(v["kem_id"].as_u64()? as u16).ok()?;
        let kdf = KdfAlgorithm::try_from(v["kdf_id"].as_u64()? as u16).ok()?;
        let aead = AeadAlgorithm::try_from(v["aead_id"].as_u64()? as u16).ok()?;
        <HpkeLibcrux>::supports_kem(kem).ok()?;
        <HpkeLibcrux>::supports_kdf(kdf).ok()?;
        Some(Suite { kem, kdf, aead })
    }

    fn derive_key_pair(suite: Suite, ikm: &[u8]) -> (HpkePrivateKey, Vec<u8>) {
        let hpke = Hpke::<HpkeLibcrux>::new(Mode::Base, suite.kem, suite.kdf, suite.aead);
        let (sk, pk) = hpke.derive_key_pair(ikm).unwrap().into_keys();
        (sk, pk.as_slice().to_vec())
    }

    /// Checks one vector end to end: key derivation, both setups, every
    /// encryption (sender seals, receiver opens) and every export.
    fn check(v: &Value) -> bool {
        let Some(suite) = suite_of(v) else {
            return false;
        };
        let psk_bytes = v.get("psk").map(hex);
        let psk_id = v.get("psk_id").map(hex);
        let psk = || {
            psk_bytes.as_ref().map(|p| Psk {
                psk: p,
                id: psk_id.as_ref().unwrap(),
            })
        };
        assert_eq!(
            v["mode"].as_u64().unwrap(),
            if psk_bytes.is_some() { 1 } else { 0 }
        );

        let (sk_r, pk_r) = derive_key_pair(suite, &hex(&v["ikmR"]));
        assert_eq!(pk_r, hex(&v["pkRm"]));
        assert_eq!(sk_r.as_slice(), hex(&v["skRm"]));

        let info = hex(&v["info"]);
        let (enc, mut sender) =
            setup_sender(suite, FixedRng(hex(&v["ikmE"])), &pk_r, &info, psk()).unwrap();
        assert_eq!(enc, hex(&v["enc"]));
        let mut receiver = setup_receiver(suite, &enc, &sk_r, &info, psk()).unwrap();

        for e in v["encryptions"].as_array().unwrap() {
            let (aad, pt, ct) = (hex(&e["aad"]), hex(&e["pt"]), hex(&e["ct"]));
            assert_eq!(sender.seal(&aad, &pt).unwrap(), ct);
            assert_eq!(receiver.open(&aad, &ct).unwrap(), pt);
        }
        for x in v["exports"].as_array().unwrap() {
            let (ctx, len) = (
                hex(&x["exporter_context"]),
                x["L"].as_u64().unwrap() as usize,
            );
            let expected = hex(&x["exported_value"]);
            assert_eq!(sender.export(&ctx, len).unwrap(), expected);
            assert_eq!(receiver.export(&ctx, len).unwrap(), expected);
        }
        true
    }

    #[test]
    fn rfc9180_x25519_base_and_psk() {
        let vectors: Value = serde_json::from_str(include_str!(
            "../tests/vectors/rfc9180-x25519-base-psk.json"
        ))
        .unwrap();
        let vectors = vectors.as_array().unwrap();
        let checked = vectors.iter().filter(|v| check(v)).count();
        // HKDF-SHA256 and -SHA512 with AES-128-GCM, AES-256-GCM, ChaCha20-Poly1305
        // and export-only, in both modes.
        assert_eq!(checked, 16);
        let psk = vectors
            .iter()
            .filter(|v| v["mode"] == 1 && suite_of(v).is_some())
            .count();
        assert_eq!(psk, 8);
    }

    #[test]
    fn draft_ietf_hpke_pq_xwing() {
        let vectors: Value =
            serde_json::from_str(include_str!("../tests/vectors/draft-ietf-hpke-pq.json")).unwrap();
        let xwing: Vec<&Value> = vectors
            .as_array()
            .unwrap()
            .iter()
            .filter(|v| v["kem_id"] == 0x647a)
            .collect();
        assert_eq!(xwing.len(), 2);
        assert!(xwing.iter().all(|v| check(v)));
        // graphite's own suite is among them.
        assert!(xwing.iter().any(|v| v["kdf_id"] == 1 && v["aead_id"] == 3));
    }

    #[test]
    fn xwing_draft_kem_vectors() {
        let vectors: Value =
            serde_json::from_str(include_str!("../tests/vectors/xwing-draft.json")).unwrap();
        let vectors = vectors.as_array().unwrap();
        assert_eq!(vectors.len(), 3);
        let alg = SUITE.kem;
        for v in vectors {
            let (pk, sk) = <HpkeLibcrux>::kem_key_gen_derand(alg, &hex(&v["seed"])).unwrap();
            assert_eq!(sk, hex(&v["sk"]));
            assert_eq!(pk, hex(&v["pk"]));
            let (ss, ct) = <HpkeLibcrux>::kem_encaps_derand(alg, &pk, &hex(&v["eseed"])).unwrap();
            assert_eq!(ct, hex(&v["ct"]));
            assert_eq!(ss, hex(&v["ss"]));
            assert_eq!(<HpkeLibcrux>::kem_decaps(alg, &ct, &sk).unwrap(), ss);
        }
    }

    #[test]
    fn suite_is_xwing_hkdf_sha256_chacha() {
        assert_eq!(SUITE.kem as u16, 0x647a);
        assert_eq!(SUITE.kdf as u16, 0x0001);
        assert_eq!(SUITE.aead as u16, 0x0003);
        let (_, pk) = generate_key_pair(SUITE).unwrap();
        assert_eq!(pk.len(), XWING_PUBLIC_KEY_LEN);
        let (enc, _) = setup_sender(SUITE, system_rng().unwrap(), &pk, b"", None).unwrap();
        assert_eq!(enc.len(), XWING_ENC_LEN);
    }

    #[test]
    fn psk_mode_needs_the_same_psk_on_both_sides() {
        let (sk, pk) = generate_key_pair(SUITE).unwrap();
        let good = Psk {
            psk: &[1; 32],
            id: b"id",
        };
        let (enc, sender) =
            setup_sender(SUITE, system_rng().unwrap(), &pk, b"info", Some(good)).unwrap();
        let ok = setup_receiver(
            SUITE,
            &enc,
            &sk,
            b"info",
            Some(Psk {
                psk: &[1; 32],
                id: b"id",
            }),
        )
        .unwrap();
        let bad = setup_receiver(
            SUITE,
            &enc,
            &sk,
            b"info",
            Some(Psk {
                psk: &[2; 32],
                id: b"id",
            }),
        )
        .unwrap();
        let base = setup_receiver(SUITE, &enc, &sk, b"info", None).unwrap();
        let x = export(&sender, b"x").unwrap();
        assert_eq!(*export(&ok, b"x").unwrap(), *x);
        assert_ne!(*export(&bad, b"x").unwrap(), *x);
        assert_ne!(*export(&base, b"x").unwrap(), *x);
    }
}
