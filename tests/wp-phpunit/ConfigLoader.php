<?php
/*
 * PHPUnit 9's XML configuration loader, replaced: tests/wp-phpunit/phpunit.php declares this class before composer's
 * autoloader could load PHPUnit's own, when PHPUNIT_CONFIG_SERIALIZED names the configuration that
 * config-export.php wrote on the reference PHP. PHPUnit then runs exactly as with the XML, without ext-dom.
 */
namespace PHPUnit\TextUI\XmlConfiguration;

final class Loader {
	public function load( string $filename ): Configuration {
		$configuration = unserialize( file_get_contents( getenv( 'PHPUNIT_CONFIG_SERIALIZED' ) ) );
		if ( ! $configuration instanceof Configuration || realpath( $configuration->filename() ) !== realpath( $filename ) ) {
			throw new Exception( 'PHPUNIT_CONFIG_SERIALIZED is not the configuration of ' . $filename );
		}
		return $configuration;
	}
}
