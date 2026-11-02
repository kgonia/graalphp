<?php
// localeconv() in the C locale (WooCommerce's wc_format_decimal reads the decimal point from it)
var_dump(localeconv());
