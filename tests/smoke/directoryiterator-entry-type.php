<?php
// Issue 062: SplFileInfo methods on a DirectoryIterator describe the current entry.
$d = sys_get_temp_dir() . '/smoke-diriter-' . getmypid();
@mkdir($d);
@mkdir("$d/sub");
file_put_contents("$d/f.txt", "abc");
$r = [];
foreach (new DirectoryIterator($d) as $f) {
    if ($f->isDot()) continue;
    $r[$f->getFilename()] = [$f->getType(), $f->isDir(), $f->isFile(), $f->isReadable(), $f->isWritable(),
        $f->getExtension(), $f->getBasename('.txt'), $f->getFileInfo()->getFilename(),
        $f->isDir() ? 0 : $f->getSize(), (string) $f, $f->getRealPath() !== false];
}
ksort($r);
echo json_encode($r), "\n";
unlink("$d/f.txt");
rmdir("$d/sub");
rmdir($d);
