use crate::Error;

pub(crate) fn random<const N: usize>() -> Result<[u8; N], Error> {
    let mut out = [0u8; N];
    getrandom::fill(&mut out).map_err(|_| Error::Randomness)?;
    Ok(out)
}

/// Fills a secret in place, so no copy is left behind to wipe.
pub(crate) fn random_secret<const N: usize>() -> Result<zeroize::Zeroizing<[u8; N]>, Error> {
    let mut out = zeroize::Zeroizing::new([0u8; N]);
    getrandom::fill(&mut out[..]).map_err(|_| Error::Randomness)?;
    Ok(out)
}
