<?php
// Issue 062: SplFileInfo methods on a DirectoryIterator describe the current entry.
$d = sys_get_temp_dir() . '/smoke-diriter-' . getmypid();
@mkdir($d);
@mkdir("$d/sub");
touch("$d/f.txt");
$r = [];
foreach (new DirectoryIterator($d) as $f) {
    if ($f->isDot()) continue;
    $r[$f->getFilename()] = ($f->isDir() ? 'dir' : 'file') . ' ' . $f->getType() . ' ' . $f->getExtension();
}
ksort($r);
echo json_encode($r), "\n";
unlink("$d/f.txt");
rmdir("$d/sub");
rmdir($d);
