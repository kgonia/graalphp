<?php
// WordPress wp-includes/class-wp-hook.php and plugin.php (GPL-2.0-or-later).
// Extract the registration and dispatch methods used by bench/gate1/kernels.php.
// The fixture registers before dispatch, with no callbacks on the special "all" hook.
final class WP_Hook {
	public $callbacks = array();
	protected $priorities = array();
	private $iterations = array();
	private $current_priority = array();
	private $nesting_level = 0;
	private $doing_action = false;

	public function add_filter( $hook_name, $callback, $priority, $accepted_args ) {
		if ( null === $priority ) {
			$priority = 0;
		}

		$idx = _wp_filter_build_unique_id( $hook_name, $callback, $priority );
		if ( null === $idx ) {
			return;
		}

		$priority_existed = isset( $this->callbacks[ $priority ] );

		$this->callbacks[ $priority ][ $idx ] = array(
			'function'      => $callback,
			'accepted_args' => (int) $accepted_args,
		);

		// If we're adding a new priority to the list, put them back in sorted order.
		if ( ! $priority_existed && count( $this->callbacks ) > 1 ) {
			ksort( $this->callbacks, SORT_NUMERIC );
		}

		$this->priorities = array_keys( $this->callbacks );

		if ( $this->nesting_level > 0 ) {
			$this->resort_active_iterations( $priority, $priority_existed );
		}
	}

	public function apply_filters( $value, $args ) {
		if ( ! $this->callbacks ) {
			return $value;
		}

		$nesting_level = $this->nesting_level++;

		$this->iterations[ $nesting_level ] = $this->priorities;

		$num_args = count( $args );

		do {
			$priority = current( $this->iterations[ $nesting_level ] );

			if ( false === $priority ) {
				// This is not expected to occur since the hook is known to have callbacks at one or more priorities.
				break;
			}

			$this->current_priority[ $nesting_level ] = $priority;

			foreach ( $this->callbacks[ $priority ] as $the_ ) {
				if ( ! $this->doing_action ) {
					$args[0] = $value;
				}

				// Avoid the array_slice() if possible.
				if ( 0 === $the_['accepted_args'] ) {
					$value = call_user_func( $the_['function'] );
				} elseif ( $the_['accepted_args'] >= $num_args ) {
					$value = call_user_func_array( $the_['function'], $args );
				} else {
					$value = call_user_func_array( $the_['function'], array_slice( $args, 0, $the_['accepted_args'] ) );
				}
			}
		} while ( false !== next( $this->iterations[ $nesting_level ] ) );

		unset( $this->iterations[ $nesting_level ] );
		unset( $this->current_priority[ $nesting_level ] );

		--$this->nesting_level;

		return $value;
	}

	public function do_action( $args ) {
		$this->doing_action = true;
		$this->apply_filters( '', $args );

		// If there are recursive calls to the current action, we haven't finished it until we get to the last one.
		if ( ! $this->nesting_level ) {
			$this->doing_action = false;
		}
	}
}

function add_filter( $hook_name, $callback, $priority = 10, $accepted_args = 1 ) {
	global $wp_filter;

	if ( ! isset( $wp_filter[ $hook_name ] ) ) {
		$wp_filter[ $hook_name ] = new WP_Hook();
	}

	$wp_filter[ $hook_name ]->add_filter( $hook_name, $callback, $priority, $accepted_args );

	return true;
}

function apply_filters( $hook_name, $value, ...$args ) {
	global $wp_filter, $wp_filters, $wp_current_filter;

	if ( ! isset( $wp_filters[ $hook_name ] ) ) {
		$wp_filters[ $hook_name ] = 1;
	} else {
		++$wp_filters[ $hook_name ];
	}

	// Do 'all' actions first.
	if ( isset( $wp_filter['all'] ) ) {
		$wp_current_filter[] = $hook_name;

		$all_args = func_get_args(); // phpcs:ignore PHPCompatibility.FunctionUse.ArgumentFunctionsReportCurrentValue.NeedsInspection
		_wp_call_all_hook( $all_args );
	}

	if ( ! isset( $wp_filter[ $hook_name ] ) ) {
		if ( isset( $wp_filter['all'] ) ) {
			array_pop( $wp_current_filter );
		}

		return $value;
	}

	if ( ! isset( $wp_filter['all'] ) ) {
		$wp_current_filter[] = $hook_name;
	}

	// Pass the value to WP_Hook.
	array_unshift( $args, $value );

	$filtered = $wp_filter[ $hook_name ]->apply_filters( $value, $args );

	array_pop( $wp_current_filter );

	return $filtered;
}

function add_action( $hook_name, $callback, $priority = 10, $accepted_args = 1 ) {
	return add_filter( $hook_name, $callback, $priority, $accepted_args );
}

function do_action( $hook_name, ...$arg ) {
	global $wp_filter, $wp_actions, $wp_current_filter;

	if ( ! isset( $wp_actions[ $hook_name ] ) ) {
		$wp_actions[ $hook_name ] = 1;
	} else {
		++$wp_actions[ $hook_name ];
	}

	// Do 'all' actions first.
	if ( isset( $wp_filter['all'] ) ) {
		$wp_current_filter[] = $hook_name;
		$all_args            = func_get_args(); // phpcs:ignore PHPCompatibility.FunctionUse.ArgumentFunctionsReportCurrentValue.NeedsInspection
		_wp_call_all_hook( $all_args );
	}

	if ( ! isset( $wp_filter[ $hook_name ] ) ) {
		if ( isset( $wp_filter['all'] ) ) {
			array_pop( $wp_current_filter );
		}

		return;
	}

	if ( ! isset( $wp_filter['all'] ) ) {
		$wp_current_filter[] = $hook_name;
	}

	if ( empty( $arg ) ) {
		$arg[] = '';
	} elseif ( is_array( $arg[0] ) && 1 === count( $arg[0] ) && isset( $arg[0][0] ) && is_object( $arg[0][0] ) ) {
		// Backward compatibility for PHP4-style passing of `array( &$this )` as action `$arg`.
		$arg[0] = $arg[0][0];
	}

	$wp_filter[ $hook_name ]->do_action( $arg );

	array_pop( $wp_current_filter );
}

function _wp_call_all_hook( $args ) {
	global $wp_filter;

	$wp_filter['all']->do_all_hook( $args );
}

function _wp_filter_build_unique_id( $hook_name, $callback, $priority ): ?string {
	if ( is_string( $callback ) ) {
		return $callback;
	}

	if ( is_object( $callback ) ) {
		/*
		 * The prefix keeps the ID from being the decimal representation of an integer. PHP casts such a
		 * string to int when it is used as an array key, which would change the type of the keys in
		 * WP_Hook::$callbacks and break consumers that pass them to string functions.
		 */
		return 'spl_object_id:' . spl_object_id( $callback );
	}

	if ( ! isset( $callback[1] ) || ! is_string( $callback[1] ) ) {
		return null;
	}

	if ( is_object( $callback[0] ) ) {
		// Object class calling.
		return ( (string) spl_object_id( $callback[0] ) ) . $callback[1];
	} elseif ( is_string( $callback[0] ) ) {
		// Static calling.
		return $callback[0] . '::' . $callback[1];
	}

	return null;
}

final class Bench_Callbacks {
    public function suffix(string $value): string {
        return $value . '|m';
    }

    public static function wrap(string $value, int $index): string {
        return '<' . $index . '>' . $value;
    }
}

function bench_upper_first(string $value): string {
    return ucfirst($value);
}

$wp_filter = [];
$wp_actions = [];
$wp_filters = [];
$wp_current_filter = [];
$headings = [' first  title ', 'second', 'third  heading'];
$kernel = static function () use ($headings) {
        $object = new Bench_Callbacks();
        $seen   = 0;
        add_filter('bench_title', 'trim');
        add_filter('bench_title', 'bench_upper_first', 5);
        add_filter('bench_title', [$object, 'suffix'], 20);
        add_filter('bench_title', ['Bench_Callbacks', 'wrap'], 20, 2);
        add_filter('bench_title', static fn(string $value): string => str_replace('  ', ' ', $value), 30);
        add_filter('bench_title', static function (string $value, int $index) use (&$seen): string {
            do_action('bench_seen', $index);
            return $index % 7 === 0 ? apply_filters('bench_nested', $value) : $value;
        }, 40, 2);
        add_action('bench_seen', static function (int $index) use (&$seen): void {
            $seen += $index;
        });
        $out = [];
        for ($round = 0; $round < 20; $round++) {
            foreach ($headings as $index => $heading) {
                $out[] = apply_filters('bench_title', $heading, $index);
            }
        }
        $out[] = $seen;
        return $out;
};
foreach ($kernel() as $value) echo $value, "\n";
