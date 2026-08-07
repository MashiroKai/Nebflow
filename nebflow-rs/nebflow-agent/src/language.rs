//! Language detector — mirrors Scala LanguageDetector.
//!
//! Unicode-range based detection. Returns a human-readable language name
//! suitable for system prompt injection.

/// Minimum characters from a non-Latin Unicode block to trigger detection.
const MIN_CHARS: usize = 3;

/// Detect the language of the input text.
/// Returns Some("Chinese"), Some("Japanese"), etc., or None for English/default.
pub fn detect(text: &str) -> Option<String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return None;
    }

    let mut cjk_unified = 0usize;
    let mut hiragana = 0usize;
    let mut katakana = 0usize;
    let mut hangul = 0usize;
    let mut arabic = 0usize;
    let mut cyrillic = 0usize;
    let mut thai = 0usize;
    let mut devanagari = 0usize;

    for ch in trimmed.chars() {
        let cp = ch as u32;
        // CJK Unified Ideographs
        if (0x4E00..=0x9FFF).contains(&cp) || (0x3400..=0x4DBF).contains(&cp) {
            cjk_unified += 1;
        } else if (0x3040..=0x309F).contains(&cp) {
            hiragana += 1;
        } else if (0x30A0..=0x30FF).contains(&cp) {
            katakana += 1;
        } else if (0xAC00..=0xD7AF).contains(&cp) {
            hangul += 1;
        } else if (0x0600..=0x06FF).contains(&cp) || (0x0750..=0x077F).contains(&cp) {
            arabic += 1;
        } else if (0x0400..=0x04FF).contains(&cp) {
            cyrillic += 1;
        } else if (0x0E00..=0x0E7F).contains(&cp) {
            thai += 1;
        } else if (0x0900..=0x097F).contains(&cp) {
            devanagari += 1;
        }
    }

    // Japanese: CJK + Hiragana/Katakana
    if (hiragana + katakana) >= MIN_CHARS {
        Some("Japanese".into())
    } else if hangul >= MIN_CHARS {
        Some("Korean".into())
    } else if cjk_unified >= MIN_CHARS {
        Some("Chinese".into())
    } else if arabic >= MIN_CHARS {
        Some("Arabic".into())
    } else if cyrillic >= MIN_CHARS {
        Some("Russian".into())
    } else if thai >= MIN_CHARS {
        Some("Thai".into())
    } else if devanagari >= MIN_CHARS {
        Some("Hindi".into())
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detect_chinese() {
        assert_eq!(
            detect("你好世界，今天天气怎么样").as_deref(),
            Some("Chinese")
        );
    }

    #[test]
    fn detect_japanese_with_hiragana() {
        assert_eq!(detect("こんにちは世界").as_deref(), Some("Japanese"));
    }

    #[test]
    fn detect_japanese_with_katakana() {
        assert_eq!(detect("ハローワールド").as_deref(), Some("Japanese"));
    }

    #[test]
    fn detect_korean() {
        assert_eq!(detect("안녕하세요 세계").as_deref(), Some("Korean"));
    }

    #[test]
    fn detect_russian() {
        assert_eq!(detect("Привет мир как дела").as_deref(), Some("Russian"));
    }

    #[test]
    fn detect_arabic() {
        assert_eq!(detect("مرحبا بالعالم كيف حالك").as_deref(), Some("Arabic"));
    }

    #[test]
    fn detect_english_returns_none() {
        assert_eq!(detect("Hello world"), None);
    }

    #[test]
    fn detect_empty_returns_none() {
        assert_eq!(detect(""), None);
        assert_eq!(detect("   "), None);
    }

    #[test]
    fn detect_short_text_returns_none() {
        // Only 2 CJK chars — below threshold
        assert_eq!(detect("你好"), None);
    }

    #[test]
    fn detect_mixed_text() {
        // Mix of English and Chinese — should detect Chinese (3+ CJK chars)
        assert_eq!(detect("Hello 你好世界 test").as_deref(), Some("Chinese"));
    }
}
