<?php
// htmlspecialchars, htmlentities, html_entity_decode, htmlspecialchars_decode and get_html_translation_table as
// php-src's html.c: every doctype and quote flag, invalid UTF-8 (ENT_SUBSTITUTE, ENT_IGNORE, none),
// ENT_DISALLOWED, double_encode=false, numeric and named entities (two code point HTML 5 ones), charsets.
$doctypes = ['HTML401' => ENT_HTML401, 'XML1' => ENT_XML1, 'XHTML' => ENT_XHTML, 'HTML5' => ENT_HTML5];
$quotes = ['COMPAT' => ENT_COMPAT, 'QUOTES' => ENT_QUOTES, 'NOQUOTES' => ENT_NOQUOTES];
$samples = [
    "plain", "<a href=\"x\">it's & more</a>", "é ü ñ © ® ™ € — … ‘’ “” • ½ ∞ ≠ ≤ ≥ ∑ √ ∫ Ω α β",
    "≂̸ ⊂⃒ fj ⟨⟩ 𝔸 𝒜 ⪯̸ <⃒ >⃒ =⃥ ∾̳", "\t\n\r\x0B\x0C\x00\x01\x1F\x7F\u{80}\u{9F}\u{A0}",
    "\u{FFFE}\u{FFFF}\u{FDD0}\u{1FFFE}\u{10FFFF}\u{E000}",
    "a\xffb", "a\xc3b", "a\xc3\xc3b", "\xe2\x82", "\xe2\x82\xac\xe2\x82", "\xf0\x9f\x98", "\xf0\x9f\x98\x80",
    "\xed\xa0\x80", "\xc0\xaf", "\xe0\x80\xaf", "\xf4\x90\x80\x80", "\xf8\x88\x80\x80\x80", "\x80\x80",
    "\xe2\x28\xa1", "\xe2\x82\x28", "\xf0\x28\x8c\xbc", "\xf0\x90\x28\xbc", "\xf0\x90\x8c\x28", "x\xc3",
    "&amp; &lt; &#38; &#x26; &#039; &apos; &quot; &copy; &nbsp; &bogus; &#; &#x; &#xZZ; & &&",
    "&#0; &#xD800; &#x10FFFF; &#x110000; &#1114111; &#65534; &#xFFFE; &#13; &#x0D; &#9; &#12; &#128; &#159;",
    "&NotEqualTilde; &nvlt; &bne; &fjlig; &ThickSpace; &Aacute; &aacute &AMP; &LT; &amp &#x0x41; &#X41; &#0065;",
    "&#99999999999999999999; &#x7FFFFFFF; &#-1; &# 1; &#+1; &a1; &1a; &a-b; &aelig;&AElig; &;",
];
foreach ($doctypes as $dn => $doctype) {
    foreach ($quotes as $qn => $quote) {
        foreach ([0, ENT_SUBSTITUTE, ENT_IGNORE, ENT_SUBSTITUTE | ENT_DISALLOWED, ENT_DISALLOWED] as $extra) {
            $flags = $doctype | $quote | $extra;
            echo "== $dn $qn $extra\n";
            foreach ($samples as $s) {
                echo bin2hex(htmlspecialchars($s, $flags)), ' ', bin2hex(htmlentities($s, $flags)), ' ',
                    bin2hex(htmlspecialchars($s, $flags, 'UTF-8', false)), ' ',
                    bin2hex(htmlentities($s, $flags, null, false)), "\n";
            }
        }
        foreach ($samples as $s) {
            $flags = $doctype | $quote;
            echo bin2hex(html_entity_decode($s, $flags)), ' ', bin2hex(htmlspecialchars_decode($s, $flags)), "\n";
        }
        $table = get_html_translation_table(HTML_ENTITIES, $doctype | $quote);
        echo count($table), ' ', md5(serialize($table)), ' ';
        $table = get_html_translation_table(HTML_SPECIALCHARS, $doctype | $quote);
        echo count($table), ' ', json_encode($table), "\n";
    }
}
// defaults
echo htmlspecialchars("<'\"&>"), ' ', htmlentities("<'\"&é>"), ' ', html_entity_decode('&lt;&#039;&apos;&quot;&eacute;'),
    ' ', htmlspecialchars_decode('&lt;&#039;&apos;&quot;&eacute;'), "\n";
var_dump(htmlspecialchars(''), htmlentities(''), html_entity_decode(''), htmlspecialchars("a\x80b", ENT_QUOTES));
// round trip of every HTML 5 entity
$all = get_html_translation_table(HTML_ENTITIES, ENT_QUOTES | ENT_HTML5);
$bad = 0;
foreach ($all as $char => $entity) {
    if (html_entity_decode($entity, ENT_QUOTES | ENT_HTML5) !== (string) $char) {
        $bad++;
    }
}
echo count($all), " entities, $bad not round-tripping\n";
echo bin2hex(htmlentities(implode('', array_keys($all)), ENT_QUOTES | ENT_HTML5)) === bin2hex(implode('', $all)) ? "same\n" : "differs\n";
// charsets
$charsets = ['ISO-8859-1', 'iso8859-1', 'ISO-8859-15', 'cp1252', 'Windows-1252', '1252', 'KOI8-R', 'cp1251',
    'win-1251', 'iso-8859-5', 'cp866', 'MacRoman', 'BIG5', 'GB2312', 'BIG5-HKSCS', 'Shift_JIS', 'EUC-JP', 'utf-8',
    'UTF-8', 'UTF8', 'bogus', ''];
$bytes = '';
for ($i = 0; $i < 256; $i++) {
    $bytes .= chr($i);
}
foreach ($charsets as $cs) {
    echo "== $cs\n";
    foreach ([ENT_QUOTES, ENT_QUOTES | ENT_SUBSTITUTE, ENT_QUOTES | ENT_HTML5 | ENT_DISALLOWED | ENT_SUBSTITUTE,
                 ENT_QUOTES | ENT_IGNORE] as $flags) {
        echo bin2hex(@htmlentities($bytes, $flags, $cs)), "\n";
        echo bin2hex(@htmlspecialchars($bytes, $flags, $cs)), "\n";
    }
    echo bin2hex(@html_entity_decode('&eacute;&euro;&#233;&#x20AC;&#x416;&Zhcy;&yen;&#92;&lt;&NotEqualTilde;&quot;', ENT_QUOTES | ENT_HTML5, $cs)), "\n";
    $table = @get_html_translation_table(HTML_ENTITIES, ENT_QUOTES | ENT_HTML5, $cs);
    echo count($table), ' ', md5(serialize($table)), "\n";
    $table = @get_html_translation_table(HTML_ENTITIES, ENT_QUOTES, $cs);
    echo count($table), ' ', md5(serialize($table)), "\n";
}
