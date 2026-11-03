//! ML-DSA-65 (FIPS 204) through libcrux, with hedged signing.

use libcrux_ml_dsa::ml_dsa_65::{
    self, MLDSA65Signature, MLDSA65SigningKey, MLDSA65VerificationKey,
};
use libcrux_ml_dsa::{KEY_GENERATION_RANDOMNESS_SIZE, SIGNING_RANDOMNESS_SIZE};
use zeroize::Zeroize;

use crate::Error;
use crate::random::random_secret;

pub(crate) const VERIFICATION_KEY_LEN: usize = 1952;
pub(crate) const SIGNATURE_LEN: usize = 3309;

/// An ephemeral ML-DSA-65 signing key, wiped on drop.
pub(crate) struct SigningKey(MLDSA65SigningKey);

impl Drop for SigningKey {
    fn drop(&mut self) {
        self.0.as_mut_slice().zeroize();
    }
}

impl SigningKey {
    pub fn generate() -> Result<(Self, [u8; VERIFICATION_KEY_LEN]), Error> {
        let seed = random_secret::<KEY_GENERATION_RANDOMNESS_SIZE>()?;
        let mut pair = ml_dsa_65::generate_key_pair(*seed);
        let verification_key = *pair.verification_key.as_ref();
        let key = SigningKey(pair.signing_key.clone());
        pair.signing_key.as_mut_slice().zeroize();
        Ok((key, verification_key))
    }

    /// Signs `message` under the FIPS 204 context string `context`.
    pub fn sign(&self, context: &[u8], message: &[u8]) -> Result<[u8; SIGNATURE_LEN], Error> {
        let randomness = random_secret::<SIGNING_RANDOMNESS_SIZE>()?;
        let signature =
            ml_dsa_65::sign(&self.0, message, context, *randomness).map_err(|_| Error::Crypto)?;
        Ok(*signature.as_ref())
    }
}

pub(crate) fn verify(
    key: &[u8; VERIFICATION_KEY_LEN],
    context: &[u8],
    message: &[u8],
    signature: &[u8; SIGNATURE_LEN],
) -> Result<(), Error> {
    ml_dsa_65::verify(
        &MLDSA65VerificationKey::new(*key),
        message,
        context,
        &MLDSA65Signature::new(*signature),
    )
    .map_err(|_| Error::SignatureInvalid)
}

#[cfg(test)]
mod tests {
    //! NIST ACVP ML-DSA-65 vectors (tests/vectors/acvp-ml-dsa-65.json): key
    //! generation from a seed, and signature verification through the
    //! external, pure interface with a context string, which is what
    //! [`verify`] uses.

    use super::*;
    use serde_json::Value;

    fn hex(v: &Value) -> Vec<u8> {
        let s = v.as_str().unwrap();
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    fn vectors() -> Value {
        serde_json::from_str(include_str!("../tests/vectors/acvp-ml-dsa-65.json")).unwrap()
    }

    #[test]
    fn acvp_key_generation() {
        let v = vectors();
        let cases = v["keyGen"].as_array().unwrap();
        assert_eq!(cases.len(), 25);
        for t in cases {
            let pair = ml_dsa_65::generate_key_pair(hex(&t["seed"]).try_into().unwrap());
            assert_eq!(
                pair.verification_key.as_slice(),
                hex(&t["pk"]),
                "{}",
                t["tcId"]
            );
            assert_eq!(pair.signing_key.as_slice(), hex(&t["sk"]), "{}", t["tcId"]);
        }
    }

    #[test]
    fn acvp_signature_verification() {
        let v = vectors();
        let cases = v["sigVer"].as_array().unwrap();
        assert_eq!(cases.len(), 15);
        for t in cases {
            let key: [u8; VERIFICATION_KEY_LEN] = hex(&t["pk"]).try_into().unwrap();
            let signature = hex(&t["signature"]);
            let result = match <[u8; SIGNATURE_LEN]>::try_from(&signature[..]) {
                Ok(sig) => verify(&key, &hex(&t["context"]), &hex(&t["message"]), &sig),
                Err(_) => Err(Error::SignatureInvalid),
            };
            assert_eq!(
                result.is_ok(),
                t["testPassed"].as_bool().unwrap(),
                "{}",
                t["tcId"]
            );
        }
    }

    #[test]
    fn own_signatures_verify_only_under_their_context() {
        let (key, vk) = SigningKey::generate().unwrap();
        let sig = key.sign(b"ctx", b"message").unwrap();
        assert_eq!(verify(&vk, b"ctx", b"message", &sig), Ok(()));
        assert_eq!(
            verify(&vk, b"other", b"message", &sig),
            Err(Error::SignatureInvalid)
        );
        assert_eq!(
            verify(&vk, b"ctx", b"messagf", &sig),
            Err(Error::SignatureInvalid)
        );
    }
}
