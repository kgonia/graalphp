<?php
try {
    thrower(2);
} catch (Exception $e) {
    echo $e->getTraceAsString(), "\n";
}
