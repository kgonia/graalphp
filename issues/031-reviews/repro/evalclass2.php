<?php
for ($i = 0; $i < 500; $i++) {
    eval('class C_' . $i . ' { public function m() { return 1; } }');
}
