#!/usr/bin/env bash
# Fetches the unmodified WordPress files the Gate 1 kernels run, and the post corpus, at pinned commits.
set -euo pipefail
cd "$(dirname "$0")"
WP=d86e2079edbf869a0393ee99a91da19351420461         # WordPress/WordPress trunk, 2026-10-22
TTD=b47acf980696897936265182cb684dca648476c7        # WordPress/theme-test-data master, 2024-05-02
FILES="class-wp-hook.php class-wp-filter-sentinel.php plugin.php utf8.php formatting.php kses.php blocks.php
       shortcodes.php class-wp-block-parser.php class-wp-block-parser-block.php class-wp-block-parser-frame.php
       class-wp-token-map.php html-api/html5-named-character-references.php html-api/class-wp-html-span.php
       html-api/class-wp-html-text-replacement.php html-api/class-wp-html-attribute-token.php
       html-api/class-wp-html-decoder.php html-api/class-wp-html-tag-processor.php"
for f in $FILES; do
  mkdir -p "wordpress/wp-includes/$(dirname "$f")"
  curl -fsSL "https://raw.githubusercontent.com/WordPress/WordPress/$WP/wp-includes/$f" -o "wordpress/wp-includes/$f"
done
mkdir -p data
for f in themeunittestdata.wordpress.xml 64-block-test-data.xml; do
  curl -fsSL "https://raw.githubusercontent.com/WordPress/theme-test-data/$TTD/$f" -o "data/$f"
done
python -I ../../tools/make_corpus.py data corpus.php
echo "fetched WordPress $WP and theme-test-data $TTD"
