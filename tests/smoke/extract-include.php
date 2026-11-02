<?php
// A file included in a function sees the variables extract() made there, also ones the function never names
// (WooCommerce's wc_get_template() extracts a template's arguments, then includes the template)
function template($file, $args = [])
{
    $located = $file;
    if (!empty($args) && is_array($args)) {
        extract($args);
    }
    include $located;
}
template(__DIR__ . '/extract-include.inc', ['options' => ['menu_order' => 'Default', 'price' => 'Price'],
    'chosen' => 'price', 'label' => false]);
template(__DIR__ . '/extract-include.inc', ['options' => []]);

