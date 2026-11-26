package pl.codeloom.php.b1.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opts a runtime method into the zero-box dispatch rules, and optionally the single-probe lookup rule. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface HotPath {
    boolean lookup() default false;
}
