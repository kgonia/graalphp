<?php
// Gate 2: after the boot, read through wpdb and the WordPress APIs on top of it. Both engines must print the same.
$_SERVER['HTTP_HOST'] = 'localhost';
$_SERVER['SERVER_NAME'] = 'localhost';
$_SERVER['REQUEST_URI'] = '/';
$_SERVER['REQUEST_METHOD'] = 'GET';
$_SERVER['SCRIPT_NAME'] = '/index.php';
$_SERVER['PHP_SELF'] = '/index.php';
require __DIR__ . '/../../target/gate2/wordpress/wp-load.php';

global $wpdb;
echo "blogname: ", get_option('blogname'), "\n";
echo "siteurl: ", get_option('siteurl'), "\n";
echo "posts: ", $wpdb->get_var("SELECT COUNT(*) FROM {$wpdb->posts} WHERE post_status = 'publish'"), "\n";
echo "users: ", implode(',', $wpdb->get_col("SELECT user_login FROM {$wpdb->users} ORDER BY ID")), "\n";
$row = $wpdb->get_row("SELECT ID, post_title, post_type FROM {$wpdb->posts} ORDER BY ID LIMIT 1", ARRAY_A);
echo "first: ", json_encode($row), "\n";
$query = new WP_Query(['post_type' => 'post', 'posts_per_page' => 10, 'orderby' => 'ID', 'order' => 'ASC']);
foreach ($query->posts as $post) {
    echo "post ", $post->ID, ": ", $post->post_title, " (", strlen($post->post_content), " bytes)\n";
}
echo "categories: ", implode(',', wp_list_pluck(get_categories(['hide_empty' => false]), 'name')), "\n";
echo "last_error: [", $wpdb->last_error, "]\n";
