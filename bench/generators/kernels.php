<?php
// Generator kernels: iteration with foreach, send loops, yield from chains and arrays, and the generators
// WordPress itself runs (WP_HTML_Open_Elements::walk_down/walk_up, PHPMailer's SMTP::iterateLines).
// Each returns a checksum of what it saw. Run with bench/generators/run.php (bench/gate1/ab.sh DRIVER=...).

function gen_range($from, $to)
{
    for ($i = $from; $i < $to; $i++) {
        yield $i;
    }
}

function gen_keyed(array $data)
{
    foreach ($data as $k => $v) {
        yield $k => $v * 2;
    }
}

function gen_accumulator()
{
    $total = 0;
    while (true) {
        $value = yield $total;
        if ($value === null) {
            return $total;
        }
        $total += $value;
    }
}

function gen_chain($depth, $n)
{
    if ($depth === 0) {
        for ($i = 0; $i < $n; $i++) {
            $sent = yield $i;
        }
        return $n;
    }
    $result = yield from gen_chain($depth - 1, $n);
    return $result + 1;
}

function gen_arrays($chunks)
{
    foreach ($chunks as $chunk) {
        yield from $chunk;
    }
    return count($chunks);
}

final class Gen_Stack
{
    private $stack = array();

    public function push($item)
    {
        $this->stack[] = $item;
    }

    public function walk_down()
    {
        $count = count($this->stack);
        for ($i = 0; $i < $count; $i++) {
            yield $this->stack[$i];
        }
    }

    public function walk_up($above_this_node = null)
    {
        $has_found_node = null === $above_this_node;
        for ($i = count($this->stack) - 1; $i >= 0; $i--) {
            $node = $this->stack[$i];
            if (!$has_found_node) {
                $has_found_node = $node === $above_this_node;
                continue;
            }
            yield $node;
        }
    }
}

function gen_iterate_lines($s)
{
    $start = 0;
    $length = strlen($s);
    for ($i = 0; $i < $length; $i++) {
        $c = $s[$i];
        if ($c === "\n" || $c === "\r") {
            yield substr($s, $start, $i - $start);
            if ($c === "\r" && $i + 1 < $length && $s[$i + 1] === "\n") {
                $i++;
            }
            $start = $i + 1;
        }
    }
    yield substr($s, $start);
}

$gen_text = '';
for ($i = 0; $i < 2000; $i++) {
    $gen_text .= "line $i of the message" . ($i % 3 === 0 ? "\r\n" : "\n");
}
$gen_stack = new Gen_Stack();
for ($i = 0; $i < 64; $i++) {
    $gen_stack->push("node$i");
}
$gen_data = range(0, 999);
$gen_chunks = array_chunk(range(0, 4999), 50);

return [
    'foreach_range' => function () {
        $sum = 0;
        foreach (gen_range(0, 100000) as $v) {
            $sum += $v;
        }
        return $sum;
    },
    'keyed' => function () use ($gen_data) {
        $sum = 0;
        for ($r = 0; $r < 50; $r++) {
            foreach (gen_keyed($gen_data) as $k => $v) {
                $sum += $k ^ $v;
            }
        }
        return $sum;
    },
    'send_loop' => function () {
        $g = gen_accumulator();
        $g->current();
        for ($i = 0; $i < 100000; $i++) {
            $g->send($i & 7);
        }
        $g->send(null);
        return $g->getReturn();
    },
    'yield_from_chain' => function () {
        $sum = 0;
        $g = gen_chain(5, 20000);
        foreach ($g as $k => $v) {
            $sum += $v;
        }
        return [$sum, $g->getReturn()];
    },
    'yield_from_arrays' => function () use ($gen_chunks) {
        $sum = 0;
        for ($r = 0; $r < 10; $r++) {
            foreach (gen_arrays($gen_chunks) as $k => $v) {
                $sum += $k + $v;
            }
        }
        return $sum;
    },
    'html_walk' => function () use ($gen_stack) {
        $seen = 0;
        for ($r = 0; $r < 500; $r++) {
            foreach ($gen_stack->walk_down() as $node) {
                $seen += strlen($node);
            }
            foreach ($gen_stack->walk_up('node40') as $node) {
                $seen += strlen($node);
            }
        }
        return $seen;
    },
    'iterate_lines' => function () use ($gen_text) {
        $lines = 0;
        $bytes = 0;
        for ($r = 0; $r < 10; $r++) {
            foreach (gen_iterate_lines($gen_text) as $line) {
                $lines++;
                $bytes += strlen($line);
            }
        }
        return [$lines, $bytes];
    },
];
