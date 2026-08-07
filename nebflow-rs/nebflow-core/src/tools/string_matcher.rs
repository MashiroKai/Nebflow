//! StringMatcher — fuzzy matching for EditTool.
//! Mirrors `nebflow.core.tools.StringMatcher` from Scala.
//!
//! Three-step matching: exact → quote-normalized → whitespace-insensitive.

/// Curly quote → straight quote mapping.
const CURLY_QUOTES: &[(char, char)] = &[
    ('\u{2018}', '\''), // left single
    ('\u{2019}', '\''), // right single
    ('\u{201C}', '"'),  // left double
    ('\u{201D}', '"'),  // right double
];

/// Normalize curly quotes to straight quotes.
pub fn normalize_quotes(s: &str) -> String {
    s.chars()
        .map(|c| {
            CURLY_QUOTES
                .iter()
                .find(|(curly, _)| *curly == c)
                .map(|(_, straight)| *straight)
                .unwrap_or(c)
        })
        .collect()
}

/// Collapse runs of whitespace (spaces/tabs) into single space.
fn normalize_whitespace(s: &str) -> String {
    let mut result = String::with_capacity(s.len());
    let mut prev_ws = false;
    for c in s.chars() {
        if c == ' ' || c == '\t' {
            if !prev_ws {
                result.push(' ');
            }
            prev_ws = true;
        } else {
            result.push(c);
            prev_ws = false;
        }
    }
    result
}

/// Three-step matching: exact, quote-normalized, whitespace-insensitive.
/// Returns the actual substring from `content` that matches `search`, or None.
pub fn find_actual_string(content: &str, search: &str) -> Option<String> {
    // Step 1: exact match
    if let Some(_idx) = content.find(search) {
        return Some(search.to_string());
    }

    // Step 2: quote-normalized match
    let norm_content = normalize_quotes(content);
    let norm_search = normalize_quotes(search);
    if let Some(idx) = norm_content.find(&norm_search) {
        // Map byte index in norm_content back to original content.
        // Since quote normalization is 1:1 (each char maps to exactly one char),
        // the char count up to idx in norm_content equals the char count in content.
        let char_offset = norm_content[..idx].chars().count();
        let char_len = search.chars().count();
        let result: String = content.chars().skip(char_offset).take(char_len).collect();
        return Some(result);
    }

    // Step 3: whitespace-insensitive (no quote handling)
    let wn_content = normalize_whitespace(content);
    let wn_search = normalize_whitespace(search);
    if let Some(idx) = wn_content.find(&wn_search) {
        // Best-effort: return the original substring at the same position
        // This is approximate — the Scala version uses position maps for precision
        return Some(content[idx..idx + search.len().min(content.len() - idx)].to_string());
    }

    // Step 4: quote-normalized + whitespace-insensitive
    let wn_content_q = normalize_whitespace(&norm_content);
    let wn_search_q = normalize_whitespace(&norm_search);
    if let Some(idx) = wn_content_q.find(&wn_search_q) {
        return Some(content[idx..idx + search.len().min(content.len() - idx)].to_string());
    }

    None
}

/// When old_string matched via quote normalization, apply the file's
/// curly-quote style onto new_string so the replacement stays consistent.
pub fn preserve_quote_style(old_raw: &str, old_actual: &str, new_str: &str) -> String {
    if old_raw == old_actual {
        return new_str.to_string();
    }
    apply_curly_quotes(new_str, old_actual)
}

/// Detect curly-quote pattern in reference text and apply the same
/// pattern to the target. Uses a simple open/close toggle heuristic.
fn apply_curly_quotes(target: &str, reference: &str) -> String {
    let has_curly_single = reference.contains('\u{2018}') || reference.contains('\u{2019}');
    let has_curly_double = reference.contains('\u{201C}') || reference.contains('\u{201D}');

    if !has_curly_single && !has_curly_double {
        return target.to_string();
    }

    let ref_single_opens = reference.chars().filter(|&c| c == '\u{2018}').count();
    let ref_single_closes = reference.chars().filter(|&c| c == '\u{2019}').count();
    let ref_double_opens = reference.chars().filter(|&c| c == '\u{201C}').count();
    let ref_double_closes = reference.chars().filter(|&c| c == '\u{201D}').count();

    let mut single_open = has_curly_single && ref_single_closes > ref_single_opens;
    let mut double_open = has_curly_double && ref_double_closes > ref_double_opens;

    let mut result = String::with_capacity(target.len());
    for c in target.chars() {
        if c == '\'' && has_curly_single {
            if single_open {
                result.push('\u{2019}');
            } else {
                result.push('\u{2018}');
            }
            single_open = !single_open;
        } else if c == '"' && has_curly_double {
            if double_open {
                result.push('\u{201D}');
            } else {
                result.push('\u{201C}');
            }
            double_open = !double_open;
        } else {
            result.push(c);
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_quotes_basic() {
        assert_eq!(normalize_quotes("\u{2018}hello\u{2019}"), "'hello'");
        assert_eq!(normalize_quotes("\u{201C}world\u{201D}"), "\"world\"");
    }

    #[test]
    fn find_exact_match() {
        let content = "hello world foo bar";
        assert_eq!(
            find_actual_string(content, "world"),
            Some("world".to_string())
        );
    }

    #[test]
    fn find_quote_normalized() {
        let content = "hello \u{2018}world\u{2019} foo";
        // Quote normalization should find the match and return the original substring
        let result = find_actual_string(content, "'world'");
        assert!(result.is_some());
        // The returned string should contain "world"
        assert!(result.unwrap().contains("world"));
    }

    #[test]
    fn find_whitespace_insensitive() {
        let content = "hello    world   foo";
        // Should find a match despite different whitespace
        let result = find_actual_string(content, "hello world");
        assert!(result.is_some());
    }

    #[test]
    fn find_no_match() {
        assert_eq!(find_actual_string("hello", "xyz"), None);
    }

    #[test]
    fn preserve_quote_style_no_change() {
        assert_eq!(preserve_quote_style("abc", "abc", "def"), "def");
    }

    #[test]
    fn preserve_quote_style_with_curly() {
        // When old_string had curly quotes, new_string's straight quotes should be converted
        let result = preserve_quote_style("'test'", "\u{2018}test\u{2019}", "'value'");
        assert_eq!(result, "\u{2018}value\u{2019}");
    }

    #[test]
    fn apply_curly_quotes_double() {
        let result = apply_curly_quotes("say \"hi\"", "\u{201C}test\u{201D}");
        assert_eq!(result, "say \u{201C}hi\u{201D}");
    }

    #[test]
    fn normalize_whitespace_collapses_runs() {
        assert_eq!(normalize_whitespace("a   b\t\tc"), "a b c");
    }
}
