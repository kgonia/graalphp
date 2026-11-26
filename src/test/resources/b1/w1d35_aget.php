<?php
function _wp_array_get( $input_array, $path, $default_value = null ) {
	// Confirm $path is valid.
	if ( ! is_array( $path ) || 0 === count( $path ) ) {
		return $default_value;
	}

	foreach ( $path as $path_element ) {
		if ( ! is_array( $input_array ) ) {
			return $default_value;
		}

		if ( is_string( $path_element )
			|| is_int( $path_element )
			|| null === $path_element
		) {
			/*
			 * Check if the path element exists in the input array.
			 * We check with `isset()` first, as it is a lot faster
			 * than `array_key_exists()`.
			 */
			if ( isset( $path_element, $input_array[ $path_element ] ) ) {
				$input_array = $input_array[ $path_element ];
				continue;
			}

			/*
			 * If `isset()` returns false, we check with `array_key_exists()`,
			 * which also checks for `null` values.
			 */
			if ( isset( $path_element ) && array_key_exists( $path_element, $input_array ) ) {
				$input_array = $input_array[ $path_element ];
				continue;
			}
		}

		return $default_value;
	}

	return $input_array;
}
$settings = ['color' => ['palette' => ['default' => [['slug' => 'black', 'color' => '#000']], 'theme' => []],
    'gradients' => null, 'custom' => true], 'typography' => ['fontSizes' => ['default' => [1, 2, 3]]],
    'spacing' => ['units' => ['px', 'em']]];

$kernels = [
    'aget' => static function () use ($settings) {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $v += count(_wp_array_get($settings, ['color', 'palette', 'default'], []));
            $v += _wp_array_get($settings, ['color', 'gradients'], 5) ?? 1;
            $v += _wp_array_get($settings, ['typography', 'lineHeight'], 2);
            $v += count(_wp_array_get($settings, ['spacing', 'units']));
        }
        return $v;
    },
];
$fn = $kernels['aget'];
$copy = $settings;
$copy['spacing']['units'][] = 'vw';
echo $fn(), "/", count($copy['spacing']['units']), "/", count($settings['spacing']['units']), "\n";

function inspect_copy($items = [], $step = 2) {
    $items[] = 'first';
    $items['02'] = 'string';
    $items['2'] = 'integer';
    $keys = '';
    foreach ($items as $key => $value) {
        $items[] = 'new';
        $keys .= $key . $value;
    }
    $i = 4;
    while ($i >= $step) {
        if ($i-- > 3) continue;
        elseif ($i <= 2 && $i != 0) break;
    }
    return $keys . '/' . ($i !== 0 && $i == 2 ? count(array_slice($items, 1)) : 0);
}
echo inspect_copy(), "\n";
