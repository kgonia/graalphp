<?php
// Included by class-early-binding.php: FromIncluded's parent was linked when this file was compiled, IncludedLater's
// implements an interface, so it was declared when its statement ran, after this file was compiled.
echo class_exists('FromIncluded', false) ? "in include: FromIncluded bound\n" : "in include: FromIncluded not bound\n";
echo class_exists('IncludedLater', false) ? "in include: IncludedLater bound\n" : "in include: IncludedLater not bound\n";
echo class_exists('ChildOfLater', false) ? "in include: ChildOfLater bound\n" : "in include: ChildOfLater not bound\n";
class FromIncluded extends Plain {}
class IncludedLater extends WithInterface {}
class ChildOfLater extends IncludedLater {}
