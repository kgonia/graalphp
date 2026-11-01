<?php
// tests/gate2/woo.sh setup: `wp eval-file` after `wp plugin activate woocommerce`, on PHP 8.5. Configures the store as
// its owner would with the onboarding wizard skipped: a US store in dollars, cash on delivery and direct bank
// transfer only, flat-rate shipping everywhere, the sample products (sample-data/sample_products.csv, its image URLs
// left out: WP_HTTP_BLOCK_EXTERNAL keeps them from downloading) with stock managed, and the coming-soon mode off.
// $args[0]: the CSV without images.

$csv = $args[0];

update_option('woocommerce_store_address', '1 Main Street');
update_option('woocommerce_store_city', 'San Francisco');
update_option('woocommerce_default_country', 'US:CA');
update_option('woocommerce_store_postcode', '94110');
update_option('woocommerce_currency', 'USD');
update_option('woocommerce_onboarding_profile', ['skipped' => true]);
update_option('woocommerce_task_list_hidden', 'yes');
update_option('woocommerce_extended_task_list_hidden', 'yes');
update_option('woocommerce_show_marketplace_suggestions', 'no');
update_option('woocommerce_allow_tracking', 'no');
update_option('woocommerce_coming_soon', 'no');
update_option('woocommerce_store_pages_only', 'no');

// payments: cash on delivery and direct bank transfer, no external gateway
foreach (WC()->payment_gateways()->payment_gateways() as $id => $gateway) {
    $settings = get_option("woocommerce_{$id}_settings", []);
    if (!is_array($settings)) {
        $settings = [];
    }
    $settings['enabled'] = in_array($id, ['cod', 'bacs'], true) ? 'yes' : 'no';
    if ($id === 'cod') {
        // virtual products may be paid on delivery too, as the sample's downloads are
        $settings['enable_for_virtual'] = 'yes';
    }
    update_option("woocommerce_{$id}_settings", $settings);
}

// shipping: the "rest of the world" zone, flat rate $5
$zone = new WC_Shipping_Zone(0);
$methods = $zone->get_shipping_methods();
if (!$methods) {
    $instance = $zone->add_shipping_method('flat_rate');
    update_option("woocommerce_flat_rate_{$instance}_settings",
        ['title' => 'Flat rate', 'tax_status' => 'taxable', 'cost' => '5']);
}

// the sample products
$result = Automattic\WooCommerce\Admin\API\OnboardingTasks::import_sample_products_from_csv($csv);
if (is_wp_error($result)) {
    WP_CLI::error($result->get_error_message());
}
WP_CLI::log(sprintf('imported %d products, %d failed, %d skipped', count($result['imported']),
    count($result['failed']), count($result['skipped'])));
foreach ($result['failed'] as $failed) {
    WP_CLI::warning(is_wp_error($failed) ? $failed->get_error_message() : print_r($failed, true));
}

// stock managed on every simple product and variation, so a run can check what its orders took
$stock = 0;
foreach (wc_get_products(['limit' => -1, 'type' => ['simple', 'variation'], 'status' => 'publish']) as $product) {
    if ($product->is_virtual() || $product->is_downloadable()) {
        continue;
    }
    $product->set_manage_stock(true);
    $product->set_stock_quantity(100000);
    $product->save();
    $stock++;
}
foreach (wc_get_products(['limit' => -1, 'type' => 'variable']) as $variable) {
    foreach ($variable->get_children() as $child) {
        $variation = wc_get_product($child);
        $variation->set_manage_stock(true);
        $variation->set_stock_quantity(100000);
        $variation->save();
        $stock++;
    }
}
WP_CLI::log("stock managed on $stock products");
