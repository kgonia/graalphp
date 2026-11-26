<?php
class Already {}
try { eval('echo "before declaration"; class Already {}'); } catch (Throwable $e) { echo 'caught'; }
