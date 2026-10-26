//! Length-prefixed lists for the web binding: wasm-bindgen cannot pass a `Vec<Vec<u8>>` or a `Vec<u32>` into Rust
//! without JS-side types, so the Kotlin side sends one `Uint8Array`. Every item is `u32-BE length ‖ bytes`;
//! a list of numbers is `u32-BE` each. Nothing is trusted: a short, long or oversized input is `InvalidInput`.

use crate::error::ErrorCode;

/// Most items in one list (the largest caller limit is 10 changes per commit, ADR-0015).
const MAX_ITEMS: usize = 16;

#[cfg_attr(not(target_arch = "wasm32"), allow(dead_code))]
pub(crate) fn decode_byte_lists(bytes: &[u8]) -> Result<Vec<Vec<u8>>, ErrorCode> {
    let mut rest = bytes;
    let mut items = Vec::new();
    while !rest.is_empty() {
        if items.len() == MAX_ITEMS {
            return Err(ErrorCode::InvalidInput);
        }
        let (len, tail) = rest
            .split_first_chunk::<4>()
            .ok_or(ErrorCode::InvalidInput)?;
        let len = usize::try_from(u32::from_be_bytes(*len)).map_err(|_| ErrorCode::InvalidInput)?;
        let (item, tail) = tail.split_at_checked(len).ok_or(ErrorCode::InvalidInput)?;
        items.push(item.to_vec());
        rest = tail;
    }
    Ok(items)
}

#[cfg_attr(not(target_arch = "wasm32"), allow(dead_code))]
pub(crate) fn decode_u32_list(bytes: &[u8]) -> Result<Vec<u32>, ErrorCode> {
    let (words, rest) = bytes.as_chunks::<4>();
    if !rest.is_empty() || words.len() > MAX_ITEMS {
        return Err(ErrorCode::InvalidInput);
    }
    Ok(words.iter().map(|w| u32::from_be_bytes(*w)).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn byte_lists_round_trip_and_malformed_input_is_refused() {
        assert_eq!(decode_byte_lists(&[]), Ok(vec![]));
        let two = [0, 0, 0, 2, 7, 8, 0, 0, 0, 0];
        assert_eq!(decode_byte_lists(&two), Ok(vec![vec![7, 8], vec![]]));
        assert_eq!(decode_byte_lists(&[0, 0, 0]), Err(ErrorCode::InvalidInput));
        assert_eq!(
            decode_byte_lists(&[0, 0, 0, 3, 1]),
            Err(ErrorCode::InvalidInput)
        );
        assert_eq!(decode_byte_lists(&[0xFF; 8]), Err(ErrorCode::InvalidInput));
        let many = [0u8; 4 * (MAX_ITEMS + 1)];
        assert_eq!(decode_byte_lists(&many), Err(ErrorCode::InvalidInput));
    }

    #[test]
    fn number_lists_need_whole_words_and_a_sane_count() {
        assert_eq!(decode_u32_list(&[0, 0, 0, 5, 0, 0, 1, 0]), Ok(vec![5, 256]));
        assert_eq!(decode_u32_list(&[0, 0, 0]), Err(ErrorCode::InvalidInput));
        assert_eq!(
            decode_u32_list(&[0u8; 4 * (MAX_ITEMS + 1)]),
            Err(ErrorCode::InvalidInput)
        );
    }
}
