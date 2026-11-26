<?php
function already() {}
try { eval('function already() {}'); } catch (Throwable $e) { echo 'caught'; }
