<?php
// included by extract-include.php inside a function: sees what extract() made
foreach ($options as $id => $name) {
    echo "$id=$name ";
}
echo $chosen ?? 'no choice', ' ', isset($label) ? 'label' : 'no label', "\n";
