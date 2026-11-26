<?php
try {
    eval('class Same { function label() { return "first"; } } echo (new Same())->label(); class Same {}');
} catch (Throwable $e) { echo 'caught'; }
