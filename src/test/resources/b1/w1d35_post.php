<?php
// WordPress wp-includes/class-wp-post.php (GPL-2.0-or-later).
// Public row fields and the constructor used by the post kernel in probe-b/php-ref/hot.php.
final class WP_Post {
	public $ID;
	public $post_author = '0';
	public $post_date = '0000-00-00 00:00:00';
	public $post_date_gmt = '0000-00-00 00:00:00';
	public $post_content = '';
	public $post_title = '';
	public $post_excerpt = '';
	public $post_status = 'publish';
	public $comment_status = 'open';
	public $ping_status = 'open';
	public $post_password = '';
	public $post_name = '';
	public $to_ping = '';
	public $pinged = '';
	public $post_modified = '0000-00-00 00:00:00';
	public $post_modified_gmt = '0000-00-00 00:00:00';
	public $post_content_filtered = '';
	public $post_parent = 0;
	public $guid = '';
	public $menu_order = 0;
	public $post_type = 'post';
	public $post_mime_type = '';
	public $comment_count = '0';
	public $filter;

	public function __construct( $post ) {
		foreach ( get_object_vars( $post ) as $key => $value ) {
			$this->$key = $value;
		}
	}
}

$post_row = new stdClass();
foreach (['ID' => 1, 'post_author' => '1', 'post_date' => '2026-11-01 10:00:00', 'post_date_gmt' => '2026-11-01 10:00:00',
    'post_content' => str_repeat('<p>Hello world</p>', 20), 'post_title' => 'Hello world!', 'post_excerpt' => '',
    'post_status' => 'publish', 'comment_status' => 'open', 'ping_status' => 'open', 'post_password' => '',
    'post_name' => 'hello-world', 'to_ping' => '', 'pinged' => '', 'post_modified' => '2026-11-01 10:00:00',
    'post_modified_gmt' => '2026-11-01 10:00:00', 'post_content_filtered' => '', 'post_parent' => 0,
    'guid' => 'http://localhost:8080/?p=1', 'menu_order' => 0, 'post_type' => 'post', 'post_mime_type' => '',
    'comment_count' => '1', 'filter' => 'raw'] as $k => $v) {
    $post_row->$k = $v;
}

$kernel = static function () use ($post_row) {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $post = new WP_Post($post_row);
            $v += $post->ID + strlen($post->post_title);
        }
        return $v;
};
echo $kernel(), "\n";
