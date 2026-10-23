<?php
// finally runs on normal completion, exceptions, return, break and continue, and a return in finally wins;
// exit() runs no finally block (shutdown functions and output buffers still end the script).
function returns() {
    try {
        return "try";
    } finally {
        echo "finally after return\n";
    }
}
echo returns(), "\n";

function overrides() {
    try {
        return "try";
    } finally {
        return "finally";
    }
}
echo overrides(), "\n";

foreach ([1, 2, 3] as $i) {
    try {
        if ($i === 1) {
            continue;
        }
        if ($i === 3) {
            break;
        }
        echo "body $i\n";
    } finally {
        echo "finally $i\n";
    }
}

try {
    try {
        throw new RuntimeException("inner");
    } finally {
        echo "finally before catch\n";
    }
} catch (RuntimeException $e) {
    echo "caught ", $e->getMessage(), "\n";
}

try {
    try {
        throw new LogicException("first");
    } catch (LogicException $e) {
        throw new RuntimeException("from catch");
    } finally {
        echo "finally after throwing catch\n";
    }
} catch (RuntimeException $e) {
    echo "caught ", $e->getMessage(), "\n";
}

register_shutdown_function(function () {
    echo "shutdown\n";
});
ob_start();
try {
    echo "before exit\n";
    exit;
} finally {
    ob_end_clean();
    echo "finally on exit\n";
}
