<?php
// Named arguments of built-in functions and methods (php-src's internal arg_info names): in any order, skipped
// parameters taking their arginfo default (a constant expression); "Argument #n ($x) not passed" for a required one,
// "must be passed explicitly, because the default value is not known" for one without a known default (array_keys's
// filter_value); "Unknown named parameter"; a variadic built-in refusing extra names ("does not accept unknown named
// parameters", after its argument count is checked); by-reference parameters by name (preg_match's matches);
// built-ins compiled specially (strlen, count, in_array, func_get_args); assert's message; built-in methods and a
// built-in class's constructor.

function rel($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
}

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function t($label, $call, $trace = false) {
    try {
        show($label, $call());
    } catch (Throwable $e) {
        echo $label, ': ', get_class($e), ': ', rel($e->getMessage()), ' (line ', $e->getLine(), ")\n";
        if ($trace) {
            echo rel($e->getTraceAsString()), "\n";
        }
    }
}

function main() {
    show('array_slice named', array_slice(array: [1, 2, 3, 4, 5], offset: 2, length: 2));
    show('array_slice reordered', array_slice(length: 2, offset: 2, array: [1, 2, 3, 4, 5]));
    show('array_slice skip length', array_slice(array: ['a' => 0, 'b' => 1], offset: 1, preserve_keys: true));
    show('array_slice mixed', array_slice(['a' => 0, 'b' => 1], preserve_keys: true, offset: 1));
    show('str_pad skip pad_string', str_pad('foo', 6, pad_type: STR_PAD_LEFT));
    show('strlen', strlen(string: 'foo'));
    show('count', count(value: [1, 2, 3]));
    show('count mode', count(mode: COUNT_RECURSIVE, value: [1, [2, 3]]));
    show('in_array strict', in_array(strict: true, needle: '1', haystack: [1]));
    show('htmlentities double_encode', htmlentities('The < character is encoded as &lt;', double_encode: false));
    show('htmlspecialchars skip flags and encoding', htmlspecialchars("<a href='x'>&amp;</a>", double_encode: false));
    show('json_encode flags', json_encode(value: ['a/b' => 'é'], flags: JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
    show('json_decode skip depth', json_decode('{"a":{"b":1}}', flags: JSON_OBJECT_AS_ARRAY));
    show('implode', implode(separator: ',', array: [1, 2]));
    show('explode limit', explode(limit: 2, separator: ',', string: 'a,b,c'));
    show('number_format skip', number_format(1234.567, decimals: 2, thousands_separator: ' '));
    show('array_keys filter', array_keys([41, 42], filter_value: 42, strict: true));
    show('str_replace count by name', (function () {
        $r = str_replace(search: 'a', replace: 'b', subject: 'aaa', count: $n);
        return [$r, $n];
    })());
    show('preg_match matches by name', (function () {
        preg_match('/(b)/', 'abc', matches: $m);
        return $m;
    })());
    show('preg_match flags skipping matches', preg_match('/b/', 'abc', flags: PREG_OFFSET_CAPTURE));
    show('array_filter mode', array_filter([1, 0, 2], mode: ARRAY_FILTER_USE_KEY, callback: fn($k) => $k > 0));
    show('usort by name', (function () {
        $a = [3, 1, 2];
        usort(callback: fn($x, $y) => $x <=> $y, array: $a);
        return $a;
    })());
    show('func_get_args by name', (function ($x) {
        return func_get_args();
    })(x: 1));

    t('not passed', fn() => array_keys(strict: true), true);
    t('default not known', fn() => array_keys([], strict: true), true);
    t('unknown', fn() => strlen(str: 'x'), true);
    t('unknown after known', fn() => str_pad(string: 'x', length: 3, nope: 1));
    t('overwrites', fn() => strlen('x', string: 'y'));
    t('variadic refuses names', fn() => array_merge([1, 2], a: [3, 4]), true);
    t('variadic refuses names, two', fn() => array_diff_key([1, 2], [3, 4], a: [5, 6]));
    t('count checked first', fn() => max(x: 1), true);
    t('sprintf extra name', fn() => sprintf('%s', value: 'x'));
    t('by-reference value', fn() => preg_match('/b/', 'abc', matches: 'literal'));

    $date = new DateTime(timezone: new DateTimeZone('UTC'), datetime: '2020-01-02 03:04:05');
    show('built-in constructor', $date->format(format: 'Y-m-d H:i'));
    show('built-in method skip', $date->setTime(minute: 30, hour: 1)->format('H:i:s'));
    $it = new ArrayIterator(array: [1, 2]);
    show('built-in class', iterator_to_array(iterator: $it, preserve_keys: false));
    t('built-in method unknown', fn() => $date->format(fmt: 'Y'));
    $e = new Exception(code: 5, message: 'named');
    show('Exception constructor', [$e->getMessage(), $e->getCode()]);

    t('assert by name', function () {
        assert(assertion: true);
        assert(assertion: false);
    });
    t('assert description by name', fn() => assert(assertion: false, description: 'Description'));
    t('assert description only', fn() => assert(description: 'Description'));
}

ini_set('zend.assertions', 1);
main();
