<?php
// Issue 057: Constants that refer to each other: an Error, not a stack overflow.
class SR { const X = self::Y; const Y = self::X; }
try { echo SR::X; } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { echo SR::Y; } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
