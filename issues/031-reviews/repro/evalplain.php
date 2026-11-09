<?php
for ($i = 0; $i < 500; $i++) {
    $v = eval('return 1 + ' . $i . ';');
}
