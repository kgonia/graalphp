/* A Bison parser, made by GNU Bison 3.8.2.  */

/* Skeleton implementation for Bison LALR(1) parsers in Java

   Copyright (C) 2007-2015, 2018-2021 Free Software Foundation, Inc.

   This program is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program.  If not, see <https://www.gnu.org/licenses/>.  */

/* As a special exception, you may create a larger work that contains
   part or all of the Bison parser skeleton and distribute that work
   under terms of your choice, so long as that work isn't itself a
   parser generator using the skeleton or a modified version thereof
   as a parser skeleton.  Alternatively, if you modify or redistribute
   the parser skeleton itself, you may (at your option) remove this
   special exception, which will cause the skeleton and the resulting
   Bison output files to be licensed under the GNU General Public
   License without this special exception.

   This special exception was added by the Free Software Foundation in
   version 2.2 of Bison.  */

/* DO NOT RELY ON FEATURES THAT ARE NOT DOCUMENTED in the manual,
   especially those whose name start with YY_ or yy_.  They are
   private implementation details that can be changed or removed.  */

package pl.codeloom.php.parser.zend.generated;



import java.text.MessageFormat;
import java.util.ArrayList;
/* "%code imports" blocks.  */
/* "src/main/grammar/zend_language_parser.y":42  */

import pl.codeloom.php.parser.zend.ZendAst;
import pl.codeloom.php.parser.zend.ZendAstDecl;
import pl.codeloom.php.parser.zend.ZendParserBase;
import pl.codeloom.php.parser.zend.zval;
import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;
import static pl.codeloom.php.parser.zend.generated.ZendLanguageParser.Lexer.*;

/* "src/main/java/pl/codeloom/php/parser/zend/generated/ZendLanguageParser.java":54  */

/**
 * A Bison parser, automatically generated from <tt>src/main/grammar/zend_language_parser.y</tt>.
 *
 * @author LALR (1) parser skeleton written by Paolo Bonzini.
 */
public final class ZendLanguageParser extends ZendParserBase
{
  /** Version number for the Bison executable that generated this parser.  */
  public static final String bisonVersion = "3.8.2";

  /** Name of the skeleton that generated this parser.  */
  public static final String bisonSkeleton = "lalr1.java";






  public enum SymbolKind
  {
    S_YYEOF(0),                    /* "end of file"  */
    S_YYerror(1),                  /* error  */
    S_YYUNDEF(2),                  /* "invalid token"  */
    S_PREC_ARROW_FUNCTION(3),      /* PREC_ARROW_FUNCTION  */
    S_4_(4),                       /* '='  */
    S_5_(5),                       /* '?'  */
    S_6_(6),                       /* ':'  */
    S_7_(7),                       /* '|'  */
    S_8_(8),                       /* '^'  */
    S_9_(9),                       /* '<'  */
    S_10_(10),                     /* '>'  */
    S_11_(11),                     /* '.'  */
    S_12_(12),                     /* '+'  */
    S_13_(13),                     /* '-'  */
    S_14_(14),                     /* '*'  */
    S_15_(15),                     /* '/'  */
    S_16_(16),                     /* '%'  */
    S_17_(17),                     /* '!'  */
    S_18_(18),                     /* '~'  */
    S_19_(19),                     /* '@'  */
    S_T_NOELSE(20),                /* T_NOELSE  */
    S_T_LNUMBER(21),               /* "integer"  */
    S_T_DNUMBER(22),               /* "floating-point number"  */
    S_T_STRING(23),                /* "identifier"  */
    S_T_NAME_FULLY_QUALIFIED(24),  /* "fully qualified name"  */
    S_T_NAME_RELATIVE(25),         /* "namespace-relative name"  */
    S_T_NAME_QUALIFIED(26),        /* "namespaced name"  */
    S_T_VARIABLE(27),              /* "variable"  */
    S_T_INLINE_HTML(28),           /* T_INLINE_HTML  */
    S_T_ENCAPSED_AND_WHITESPACE(29), /* "string content"  */
    S_T_CONSTANT_ENCAPSED_STRING(30), /* "quoted string"  */
    S_T_STRING_VARNAME(31),        /* "variable name"  */
    S_T_NUM_STRING(32),            /* "number"  */
    S_T_INCLUDE(33),               /* "'include'"  */
    S_T_INCLUDE_ONCE(34),          /* "'include_once'"  */
    S_T_EVAL(35),                  /* "'eval'"  */
    S_T_REQUIRE(36),               /* "'require'"  */
    S_T_REQUIRE_ONCE(37),          /* "'require_once'"  */
    S_T_LOGICAL_OR(38),            /* "'or'"  */
    S_T_LOGICAL_XOR(39),           /* "'xor'"  */
    S_T_LOGICAL_AND(40),           /* "'and'"  */
    S_T_PRINT(41),                 /* "'print'"  */
    S_T_YIELD(42),                 /* "'yield'"  */
    S_T_YIELD_FROM(43),            /* "'yield from'"  */
    S_T_INSTANCEOF(44),            /* "'instanceof'"  */
    S_T_NEW(45),                   /* "'new'"  */
    S_T_CLONE(46),                 /* "'clone'"  */
    S_T_EXIT(47),                  /* "'exit'"  */
    S_T_IF(48),                    /* "'if'"  */
    S_T_ELSEIF(49),                /* "'elseif'"  */
    S_T_ELSE(50),                  /* "'else'"  */
    S_T_ENDIF(51),                 /* "'endif'"  */
    S_T_ECHO(52),                  /* "'echo'"  */
    S_T_DO(53),                    /* "'do'"  */
    S_T_WHILE(54),                 /* "'while'"  */
    S_T_ENDWHILE(55),              /* "'endwhile'"  */
    S_T_FOR(56),                   /* "'for'"  */
    S_T_ENDFOR(57),                /* "'endfor'"  */
    S_T_FOREACH(58),               /* "'foreach'"  */
    S_T_ENDFOREACH(59),            /* "'endforeach'"  */
    S_T_DECLARE(60),               /* "'declare'"  */
    S_T_ENDDECLARE(61),            /* "'enddeclare'"  */
    S_T_AS(62),                    /* "'as'"  */
    S_T_SWITCH(63),                /* "'switch'"  */
    S_T_ENDSWITCH(64),             /* "'endswitch'"  */
    S_T_CASE(65),                  /* "'case'"  */
    S_T_DEFAULT(66),               /* "'default'"  */
    S_T_MATCH(67),                 /* "'match'"  */
    S_T_BREAK(68),                 /* "'break'"  */
    S_T_CONTINUE(69),              /* "'continue'"  */
    S_T_GOTO(70),                  /* "'goto'"  */
    S_T_FUNCTION(71),              /* "'function'"  */
    S_T_FN(72),                    /* "'fn'"  */
    S_T_CONST(73),                 /* "'const'"  */
    S_T_RETURN(74),                /* "'return'"  */
    S_T_TRY(75),                   /* "'try'"  */
    S_T_CATCH(76),                 /* "'catch'"  */
    S_T_FINALLY(77),               /* "'finally'"  */
    S_T_THROW(78),                 /* "'throw'"  */
    S_T_USE(79),                   /* "'use'"  */
    S_T_INSTEADOF(80),             /* "'insteadof'"  */
    S_T_GLOBAL(81),                /* "'global'"  */
    S_T_STATIC(82),                /* "'static'"  */
    S_T_ABSTRACT(83),              /* "'abstract'"  */
    S_T_FINAL(84),                 /* "'final'"  */
    S_T_PRIVATE(85),               /* "'private'"  */
    S_T_PROTECTED(86),             /* "'protected'"  */
    S_T_PUBLIC(87),                /* "'public'"  */
    S_T_PRIVATE_SET(88),           /* "'private(set)'"  */
    S_T_PROTECTED_SET(89),         /* "'protected(set)'"  */
    S_T_PUBLIC_SET(90),            /* "'public(set)'"  */
    S_T_READONLY(91),              /* "'readonly'"  */
    S_T_VAR(92),                   /* "'var'"  */
    S_T_UNSET(93),                 /* "'unset'"  */
    S_T_ISSET(94),                 /* "'isset'"  */
    S_T_EMPTY(95),                 /* "'empty'"  */
    S_T_HALT_COMPILER(96),         /* "'__halt_compiler'"  */
    S_T_CLASS(97),                 /* "'class'"  */
    S_T_TRAIT(98),                 /* "'trait'"  */
    S_T_INTERFACE(99),             /* "'interface'"  */
    S_T_ENUM(100),                 /* "'enum'"  */
    S_T_EXTENDS(101),              /* "'extends'"  */
    S_T_IMPLEMENTS(102),           /* "'implements'"  */
    S_T_NAMESPACE(103),            /* "'namespace'"  */
    S_T_LIST(104),                 /* "'list'"  */
    S_T_ARRAY(105),                /* "'array'"  */
    S_T_CALLABLE(106),             /* "'callable'"  */
    S_T_LINE(107),                 /* "'__LINE__'"  */
    S_T_FILE(108),                 /* "'__FILE__'"  */
    S_T_DIR(109),                  /* "'__DIR__'"  */
    S_T_CLASS_C(110),              /* "'__CLASS__'"  */
    S_T_TRAIT_C(111),              /* "'__TRAIT__'"  */
    S_T_METHOD_C(112),             /* "'__METHOD__'"  */
    S_T_FUNC_C(113),               /* "'__FUNCTION__'"  */
    S_T_PROPERTY_C(114),           /* "'__PROPERTY__'"  */
    S_T_NS_C(115),                 /* "'__NAMESPACE__'"  */
    S_T_ATTRIBUTE(116),            /* "'#['"  */
    S_T_PLUS_EQUAL(117),           /* "'+='"  */
    S_T_MINUS_EQUAL(118),          /* "'-='"  */
    S_T_MUL_EQUAL(119),            /* "'*='"  */
    S_T_DIV_EQUAL(120),            /* "'/='"  */
    S_T_CONCAT_EQUAL(121),         /* "'.='"  */
    S_T_MOD_EQUAL(122),            /* "'%='"  */
    S_T_AND_EQUAL(123),            /* "'&='"  */
    S_T_OR_EQUAL(124),             /* "'|='"  */
    S_T_XOR_EQUAL(125),            /* "'^='"  */
    S_T_SL_EQUAL(126),             /* "'<<='"  */
    S_T_SR_EQUAL(127),             /* "'>>='"  */
    S_T_COALESCE_EQUAL(128),       /* "'??='"  */
    S_T_BOOLEAN_OR(129),           /* "'||'"  */
    S_T_BOOLEAN_AND(130),          /* "'&&'"  */
    S_T_IS_EQUAL(131),             /* "'=='"  */
    S_T_IS_NOT_EQUAL(132),         /* "'!='"  */
    S_T_IS_IDENTICAL(133),         /* "'==='"  */
    S_T_IS_NOT_IDENTICAL(134),     /* "'!=='"  */
    S_T_IS_SMALLER_OR_EQUAL(135),  /* "'<='"  */
    S_T_IS_GREATER_OR_EQUAL(136),  /* "'>='"  */
    S_T_SPACESHIP(137),            /* "'<=>'"  */
    S_T_SL(138),                   /* "'<<'"  */
    S_T_SR(139),                   /* "'>>'"  */
    S_T_INC(140),                  /* "'++'"  */
    S_T_DEC(141),                  /* "'--'"  */
    S_T_INT_CAST(142),             /* "'(int)'"  */
    S_T_DOUBLE_CAST(143),          /* "'(float)'"  */
    S_T_STRING_CAST(144),          /* "'(string)'"  */
    S_T_ARRAY_CAST(145),           /* "'(array)'"  */
    S_T_OBJECT_CAST(146),          /* "'(object)'"  */
    S_T_BOOL_CAST(147),            /* "'(bool)'"  */
    S_T_UNSET_CAST(148),           /* "'(unset)'"  */
    S_T_VOID_CAST(149),            /* "'(void)'"  */
    S_T_OBJECT_OPERATOR(150),      /* "'->'"  */
    S_T_NULLSAFE_OBJECT_OPERATOR(151), /* "'?->'"  */
    S_T_DOUBLE_ARROW(152),         /* "'=>'"  */
    S_T_COMMENT(153),              /* "comment"  */
    S_T_DOC_COMMENT(154),          /* "doc comment"  */
    S_T_OPEN_TAG(155),             /* "open tag"  */
    S_T_OPEN_TAG_WITH_ECHO(156),   /* "'<?='"  */
    S_T_CLOSE_TAG(157),            /* "'?>'"  */
    S_T_WHITESPACE(158),           /* "whitespace"  */
    S_T_START_HEREDOC(159),        /* "heredoc start"  */
    S_T_END_HEREDOC(160),          /* "heredoc end"  */
    S_T_DOLLAR_OPEN_CURLY_BRACES(161), /* "'${'"  */
    S_T_CURLY_OPEN(162),           /* "'{$'"  */
    S_T_PAAMAYIM_NEKUDOTAYIM(163), /* "'::'"  */
    S_T_NS_SEPARATOR(164),         /* "'\\'"  */
    S_T_ELLIPSIS(165),             /* "'...'"  */
    S_T_COALESCE(166),             /* "'??'"  */
    S_T_POW(167),                  /* "'**'"  */
    S_T_POW_EQUAL(168),            /* "'**='"  */
    S_T_PIPE(169),                 /* "'|>'"  */
    S_T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG(170), /* "'&'"  */
    S_T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG(171), /* "amp"  */
    S_T_BAD_CHARACTER(172),        /* "invalid character"  */
    S_T_ERROR(173),                /* T_ERROR  */
    S_174_(174),                   /* ','  */
    S_175_(175),                   /* ']'  */
    S_176_(176),                   /* ';'  */
    S_177_(177),                   /* '('  */
    S_178_(178),                   /* ')'  */
    S_179_(179),                   /* '{'  */
    S_180_(180),                   /* '}'  */
    S_181_(181),                   /* '['  */
    S_182_(182),                   /* '`'  */
    S_183_(183),                   /* '"'  */
    S_184_(184),                   /* '$'  */
    S_YYACCEPT(185),               /* $accept  */
    S_start(186),                  /* start  */
    S_reserved_non_modifiers(187), /* reserved_non_modifiers  */
    S_semi_reserved(188),          /* semi_reserved  */
    S_ampersand(189),              /* ampersand  */
    S_identifier(190),             /* identifier  */
    S_top_statement_list(191),     /* top_statement_list  */
    S_namespace_declaration_name(192), /* namespace_declaration_name  */
    S_namespace_name(193),         /* namespace_name  */
    S_legacy_namespace_name(194),  /* legacy_namespace_name  */
    S_name(195),                   /* name  */
    S_attribute_decl(196),         /* attribute_decl  */
    S_attribute_group(197),        /* attribute_group  */
    S_attribute(198),              /* attribute  */
    S_attributes(199),             /* attributes  */
    S_attributed_statement(200),   /* attributed_statement  */
    S_attributed_top_statement(201), /* attributed_top_statement  */
    S_top_statement(202),          /* top_statement  */
    S_203_1(203),                  /* $@1  */
    S_204_2(204),                  /* $@2  */
    S_use_type(205),               /* use_type  */
    S_group_use_declaration(206),  /* group_use_declaration  */
    S_mixed_group_use_declaration(207), /* mixed_group_use_declaration  */
    S_possible_comma(208),         /* possible_comma  */
    S_inline_use_declarations(209), /* inline_use_declarations  */
    S_unprefixed_use_declarations(210), /* unprefixed_use_declarations  */
    S_use_declarations(211),       /* use_declarations  */
    S_inline_use_declaration(212), /* inline_use_declaration  */
    S_unprefixed_use_declaration(213), /* unprefixed_use_declaration  */
    S_use_declaration(214),        /* use_declaration  */
    S_const_list(215),             /* const_list  */
    S_inner_statement_list(216),   /* inner_statement_list  */
    S_inner_statement(217),        /* inner_statement  */
    S_statement(218),              /* statement  */
    S_219_3(219),                  /* $@3  */
    S_catch_list(220),             /* catch_list  */
    S_catch_name_list(221),        /* catch_name_list  */
    S_optional_variable(222),      /* optional_variable  */
    S_finally_statement(223),      /* finally_statement  */
    S_unset_variables(224),        /* unset_variables  */
    S_unset_variable(225),         /* unset_variable  */
    S_function_name(226),          /* function_name  */
    S_function_declaration_statement(227), /* function_declaration_statement  */
    S_is_reference(228),           /* is_reference  */
    S_is_variadic(229),            /* is_variadic  */
    S_class_declaration_statement(230), /* class_declaration_statement  */
    S_231_4(231),                  /* @4  */
    S_232_5(232),                  /* @5  */
    S_class_modifiers(233),        /* class_modifiers  */
    S_anonymous_class_modifiers(234), /* anonymous_class_modifiers  */
    S_anonymous_class_modifiers_optional(235), /* anonymous_class_modifiers_optional  */
    S_class_modifier(236),         /* class_modifier  */
    S_trait_declaration_statement(237), /* trait_declaration_statement  */
    S_238_6(238),                  /* @6  */
    S_interface_declaration_statement(239), /* interface_declaration_statement  */
    S_240_7(240),                  /* @7  */
    S_enum_declaration_statement(241), /* enum_declaration_statement  */
    S_242_8(242),                  /* @8  */
    S_enum_backing_type(243),      /* enum_backing_type  */
    S_enum_case(244),              /* enum_case  */
    S_enum_case_expr(245),         /* enum_case_expr  */
    S_extends_from(246),           /* extends_from  */
    S_interface_extends_list(247), /* interface_extends_list  */
    S_implements_list(248),        /* implements_list  */
    S_foreach_variable(249),       /* foreach_variable  */
    S_for_statement(250),          /* for_statement  */
    S_foreach_statement(251),      /* foreach_statement  */
    S_declare_statement(252),      /* declare_statement  */
    S_switch_case_list(253),       /* switch_case_list  */
    S_case_list(254),              /* case_list  */
    S_match(255),                  /* match  */
    S_match_arm_list(256),         /* match_arm_list  */
    S_non_empty_match_arm_list(257), /* non_empty_match_arm_list  */
    S_match_arm(258),              /* match_arm  */
    S_match_arm_cond_list(259),    /* match_arm_cond_list  */
    S_while_statement(260),        /* while_statement  */
    S_if_stmt_without_else(261),   /* if_stmt_without_else  */
    S_if_stmt(262),                /* if_stmt  */
    S_alt_if_stmt_without_else(263), /* alt_if_stmt_without_else  */
    S_alt_if_stmt(264),            /* alt_if_stmt  */
    S_parameter_list(265),         /* parameter_list  */
    S_non_empty_parameter_list(266), /* non_empty_parameter_list  */
    S_attributed_parameter(267),   /* attributed_parameter  */
    S_optional_cpp_modifiers(268), /* optional_cpp_modifiers  */
    S_parameter(269),              /* parameter  */
    S_optional_type_without_static(270), /* optional_type_without_static  */
    S_type_expr(271),              /* type_expr  */
    S_type(272),                   /* type  */
    S_union_type_element(273),     /* union_type_element  */
    S_union_type(274),             /* union_type  */
    S_intersection_type(275),      /* intersection_type  */
    S_type_expr_without_static(276), /* type_expr_without_static  */
    S_type_without_static(277),    /* type_without_static  */
    S_union_type_without_static_element(278), /* union_type_without_static_element  */
    S_union_type_without_static(279), /* union_type_without_static  */
    S_intersection_type_without_static(280), /* intersection_type_without_static  */
    S_return_type(281),            /* return_type  */
    S_argument_list(282),          /* argument_list  */
    S_non_empty_argument_list(283), /* non_empty_argument_list  */
    S_clone_argument_list(284),    /* clone_argument_list  */
    S_non_empty_clone_argument_list(285), /* non_empty_clone_argument_list  */
    S_argument_no_expr(286),       /* argument_no_expr  */
    S_argument(287),               /* argument  */
    S_global_var_list(288),        /* global_var_list  */
    S_global_var(289),             /* global_var  */
    S_static_var_list(290),        /* static_var_list  */
    S_static_var(291),             /* static_var  */
    S_class_statement_list(292),   /* class_statement_list  */
    S_attributed_class_statement(293), /* attributed_class_statement  */
    S_class_statement(294),        /* class_statement  */
    S_class_name_list(295),        /* class_name_list  */
    S_trait_adaptations(296),      /* trait_adaptations  */
    S_trait_adaptation_list(297),  /* trait_adaptation_list  */
    S_trait_adaptation(298),       /* trait_adaptation  */
    S_trait_precedence(299),       /* trait_precedence  */
    S_trait_alias(300),            /* trait_alias  */
    S_trait_method_reference(301), /* trait_method_reference  */
    S_absolute_trait_method_reference(302), /* absolute_trait_method_reference  */
    S_method_body(303),            /* method_body  */
    S_property_modifiers(304),     /* property_modifiers  */
    S_method_modifiers(305),       /* method_modifiers  */
    S_class_const_modifiers(306),  /* class_const_modifiers  */
    S_non_empty_member_modifiers(307), /* non_empty_member_modifiers  */
    S_member_modifier(308),        /* member_modifier  */
    S_property_list(309),          /* property_list  */
    S_property(310),               /* property  */
    S_hooked_property(311),        /* hooked_property  */
    S_property_hook_list(312),     /* property_hook_list  */
    S_optional_property_hook_list(313), /* optional_property_hook_list  */
    S_property_hook_modifiers(314), /* property_hook_modifiers  */
    S_property_hook(315),          /* property_hook  */
    S_316_9(316),                  /* @9  */
    S_property_hook_body(317),     /* property_hook_body  */
    S_optional_parameter_list(318), /* optional_parameter_list  */
    S_class_const_list(319),       /* class_const_list  */
    S_class_const_decl(320),       /* class_const_decl  */
    S_const_decl(321),             /* const_decl  */
    S_echo_expr_list(322),         /* echo_expr_list  */
    S_echo_expr(323),              /* echo_expr  */
    S_for_cond_exprs(324),         /* for_cond_exprs  */
    S_for_exprs(325),              /* for_exprs  */
    S_non_empty_for_exprs(326),    /* non_empty_for_exprs  */
    S_anonymous_class(327),        /* anonymous_class  */
    S_328_10(328),                 /* @10  */
    S_new_dereferenceable(329),    /* new_dereferenceable  */
    S_new_non_dereferenceable(330), /* new_non_dereferenceable  */
    S_expr(331),                   /* expr  */
    S_inline_function(332),        /* inline_function  */
    S_fn(333),                     /* fn  */
    S_function(334),               /* function  */
    S_backup_doc_comment(335),     /* backup_doc_comment  */
    S_backup_fn_flags(336),        /* backup_fn_flags  */
    S_backup_lex_pos(337),         /* backup_lex_pos  */
    S_returns_ref(338),            /* returns_ref  */
    S_lexical_vars(339),           /* lexical_vars  */
    S_lexical_var_list(340),       /* lexical_var_list  */
    S_lexical_var(341),            /* lexical_var  */
    S_function_call(342),          /* function_call  */
    S_343_11(343),                 /* @11  */
    S_class_name(344),             /* class_name  */
    S_class_name_reference(345),   /* class_name_reference  */
    S_backticks_expr(346),         /* backticks_expr  */
    S_ctor_arguments(347),         /* ctor_arguments  */
    S_dereferenceable_scalar(348), /* dereferenceable_scalar  */
    S_scalar(349),                 /* scalar  */
    S_constant(350),               /* constant  */
    S_class_constant(351),         /* class_constant  */
    S_optional_expr(352),          /* optional_expr  */
    S_variable_class_name(353),    /* variable_class_name  */
    S_fully_dereferenceable(354),  /* fully_dereferenceable  */
    S_array_object_dereferenceable(355), /* array_object_dereferenceable  */
    S_callable_expr(356),          /* callable_expr  */
    S_callable_variable(357),      /* callable_variable  */
    S_variable(358),               /* variable  */
    S_simple_variable(359),        /* simple_variable  */
    S_static_member(360),          /* static_member  */
    S_new_variable(361),           /* new_variable  */
    S_member_name(362),            /* member_name  */
    S_property_name(363),          /* property_name  */
    S_array_pair_list(364),        /* array_pair_list  */
    S_possible_array_pair(365),    /* possible_array_pair  */
    S_non_empty_array_pair_list(366), /* non_empty_array_pair_list  */
    S_array_pair(367),             /* array_pair  */
    S_encaps_list(368),            /* encaps_list  */
    S_encaps_var(369),             /* encaps_var  */
    S_encaps_var_offset(370),      /* encaps_var_offset  */
    S_internal_functions_in_yacc(371), /* internal_functions_in_yacc  */
    S_isset_variables(372),        /* isset_variables  */
    S_isset_variable(373);         /* isset_variable  */


    private final int yycode_;

    SymbolKind (int n) {
      this.yycode_ = n;
    }

    private static final SymbolKind[] values_ = {
      SymbolKind.S_YYEOF,
      SymbolKind.S_YYerror,
      SymbolKind.S_YYUNDEF,
      SymbolKind.S_PREC_ARROW_FUNCTION,
      SymbolKind.S_4_,
      SymbolKind.S_5_,
      SymbolKind.S_6_,
      SymbolKind.S_7_,
      SymbolKind.S_8_,
      SymbolKind.S_9_,
      SymbolKind.S_10_,
      SymbolKind.S_11_,
      SymbolKind.S_12_,
      SymbolKind.S_13_,
      SymbolKind.S_14_,
      SymbolKind.S_15_,
      SymbolKind.S_16_,
      SymbolKind.S_17_,
      SymbolKind.S_18_,
      SymbolKind.S_19_,
      SymbolKind.S_T_NOELSE,
      SymbolKind.S_T_LNUMBER,
      SymbolKind.S_T_DNUMBER,
      SymbolKind.S_T_STRING,
      SymbolKind.S_T_NAME_FULLY_QUALIFIED,
      SymbolKind.S_T_NAME_RELATIVE,
      SymbolKind.S_T_NAME_QUALIFIED,
      SymbolKind.S_T_VARIABLE,
      SymbolKind.S_T_INLINE_HTML,
      SymbolKind.S_T_ENCAPSED_AND_WHITESPACE,
      SymbolKind.S_T_CONSTANT_ENCAPSED_STRING,
      SymbolKind.S_T_STRING_VARNAME,
      SymbolKind.S_T_NUM_STRING,
      SymbolKind.S_T_INCLUDE,
      SymbolKind.S_T_INCLUDE_ONCE,
      SymbolKind.S_T_EVAL,
      SymbolKind.S_T_REQUIRE,
      SymbolKind.S_T_REQUIRE_ONCE,
      SymbolKind.S_T_LOGICAL_OR,
      SymbolKind.S_T_LOGICAL_XOR,
      SymbolKind.S_T_LOGICAL_AND,
      SymbolKind.S_T_PRINT,
      SymbolKind.S_T_YIELD,
      SymbolKind.S_T_YIELD_FROM,
      SymbolKind.S_T_INSTANCEOF,
      SymbolKind.S_T_NEW,
      SymbolKind.S_T_CLONE,
      SymbolKind.S_T_EXIT,
      SymbolKind.S_T_IF,
      SymbolKind.S_T_ELSEIF,
      SymbolKind.S_T_ELSE,
      SymbolKind.S_T_ENDIF,
      SymbolKind.S_T_ECHO,
      SymbolKind.S_T_DO,
      SymbolKind.S_T_WHILE,
      SymbolKind.S_T_ENDWHILE,
      SymbolKind.S_T_FOR,
      SymbolKind.S_T_ENDFOR,
      SymbolKind.S_T_FOREACH,
      SymbolKind.S_T_ENDFOREACH,
      SymbolKind.S_T_DECLARE,
      SymbolKind.S_T_ENDDECLARE,
      SymbolKind.S_T_AS,
      SymbolKind.S_T_SWITCH,
      SymbolKind.S_T_ENDSWITCH,
      SymbolKind.S_T_CASE,
      SymbolKind.S_T_DEFAULT,
      SymbolKind.S_T_MATCH,
      SymbolKind.S_T_BREAK,
      SymbolKind.S_T_CONTINUE,
      SymbolKind.S_T_GOTO,
      SymbolKind.S_T_FUNCTION,
      SymbolKind.S_T_FN,
      SymbolKind.S_T_CONST,
      SymbolKind.S_T_RETURN,
      SymbolKind.S_T_TRY,
      SymbolKind.S_T_CATCH,
      SymbolKind.S_T_FINALLY,
      SymbolKind.S_T_THROW,
      SymbolKind.S_T_USE,
      SymbolKind.S_T_INSTEADOF,
      SymbolKind.S_T_GLOBAL,
      SymbolKind.S_T_STATIC,
      SymbolKind.S_T_ABSTRACT,
      SymbolKind.S_T_FINAL,
      SymbolKind.S_T_PRIVATE,
      SymbolKind.S_T_PROTECTED,
      SymbolKind.S_T_PUBLIC,
      SymbolKind.S_T_PRIVATE_SET,
      SymbolKind.S_T_PROTECTED_SET,
      SymbolKind.S_T_PUBLIC_SET,
      SymbolKind.S_T_READONLY,
      SymbolKind.S_T_VAR,
      SymbolKind.S_T_UNSET,
      SymbolKind.S_T_ISSET,
      SymbolKind.S_T_EMPTY,
      SymbolKind.S_T_HALT_COMPILER,
      SymbolKind.S_T_CLASS,
      SymbolKind.S_T_TRAIT,
      SymbolKind.S_T_INTERFACE,
      SymbolKind.S_T_ENUM,
      SymbolKind.S_T_EXTENDS,
      SymbolKind.S_T_IMPLEMENTS,
      SymbolKind.S_T_NAMESPACE,
      SymbolKind.S_T_LIST,
      SymbolKind.S_T_ARRAY,
      SymbolKind.S_T_CALLABLE,
      SymbolKind.S_T_LINE,
      SymbolKind.S_T_FILE,
      SymbolKind.S_T_DIR,
      SymbolKind.S_T_CLASS_C,
      SymbolKind.S_T_TRAIT_C,
      SymbolKind.S_T_METHOD_C,
      SymbolKind.S_T_FUNC_C,
      SymbolKind.S_T_PROPERTY_C,
      SymbolKind.S_T_NS_C,
      SymbolKind.S_T_ATTRIBUTE,
      SymbolKind.S_T_PLUS_EQUAL,
      SymbolKind.S_T_MINUS_EQUAL,
      SymbolKind.S_T_MUL_EQUAL,
      SymbolKind.S_T_DIV_EQUAL,
      SymbolKind.S_T_CONCAT_EQUAL,
      SymbolKind.S_T_MOD_EQUAL,
      SymbolKind.S_T_AND_EQUAL,
      SymbolKind.S_T_OR_EQUAL,
      SymbolKind.S_T_XOR_EQUAL,
      SymbolKind.S_T_SL_EQUAL,
      SymbolKind.S_T_SR_EQUAL,
      SymbolKind.S_T_COALESCE_EQUAL,
      SymbolKind.S_T_BOOLEAN_OR,
      SymbolKind.S_T_BOOLEAN_AND,
      SymbolKind.S_T_IS_EQUAL,
      SymbolKind.S_T_IS_NOT_EQUAL,
      SymbolKind.S_T_IS_IDENTICAL,
      SymbolKind.S_T_IS_NOT_IDENTICAL,
      SymbolKind.S_T_IS_SMALLER_OR_EQUAL,
      SymbolKind.S_T_IS_GREATER_OR_EQUAL,
      SymbolKind.S_T_SPACESHIP,
      SymbolKind.S_T_SL,
      SymbolKind.S_T_SR,
      SymbolKind.S_T_INC,
      SymbolKind.S_T_DEC,
      SymbolKind.S_T_INT_CAST,
      SymbolKind.S_T_DOUBLE_CAST,
      SymbolKind.S_T_STRING_CAST,
      SymbolKind.S_T_ARRAY_CAST,
      SymbolKind.S_T_OBJECT_CAST,
      SymbolKind.S_T_BOOL_CAST,
      SymbolKind.S_T_UNSET_CAST,
      SymbolKind.S_T_VOID_CAST,
      SymbolKind.S_T_OBJECT_OPERATOR,
      SymbolKind.S_T_NULLSAFE_OBJECT_OPERATOR,
      SymbolKind.S_T_DOUBLE_ARROW,
      SymbolKind.S_T_COMMENT,
      SymbolKind.S_T_DOC_COMMENT,
      SymbolKind.S_T_OPEN_TAG,
      SymbolKind.S_T_OPEN_TAG_WITH_ECHO,
      SymbolKind.S_T_CLOSE_TAG,
      SymbolKind.S_T_WHITESPACE,
      SymbolKind.S_T_START_HEREDOC,
      SymbolKind.S_T_END_HEREDOC,
      SymbolKind.S_T_DOLLAR_OPEN_CURLY_BRACES,
      SymbolKind.S_T_CURLY_OPEN,
      SymbolKind.S_T_PAAMAYIM_NEKUDOTAYIM,
      SymbolKind.S_T_NS_SEPARATOR,
      SymbolKind.S_T_ELLIPSIS,
      SymbolKind.S_T_COALESCE,
      SymbolKind.S_T_POW,
      SymbolKind.S_T_POW_EQUAL,
      SymbolKind.S_T_PIPE,
      SymbolKind.S_T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG,
      SymbolKind.S_T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG,
      SymbolKind.S_T_BAD_CHARACTER,
      SymbolKind.S_T_ERROR,
      SymbolKind.S_174_,
      SymbolKind.S_175_,
      SymbolKind.S_176_,
      SymbolKind.S_177_,
      SymbolKind.S_178_,
      SymbolKind.S_179_,
      SymbolKind.S_180_,
      SymbolKind.S_181_,
      SymbolKind.S_182_,
      SymbolKind.S_183_,
      SymbolKind.S_184_,
      SymbolKind.S_YYACCEPT,
      SymbolKind.S_start,
      SymbolKind.S_reserved_non_modifiers,
      SymbolKind.S_semi_reserved,
      SymbolKind.S_ampersand,
      SymbolKind.S_identifier,
      SymbolKind.S_top_statement_list,
      SymbolKind.S_namespace_declaration_name,
      SymbolKind.S_namespace_name,
      SymbolKind.S_legacy_namespace_name,
      SymbolKind.S_name,
      SymbolKind.S_attribute_decl,
      SymbolKind.S_attribute_group,
      SymbolKind.S_attribute,
      SymbolKind.S_attributes,
      SymbolKind.S_attributed_statement,
      SymbolKind.S_attributed_top_statement,
      SymbolKind.S_top_statement,
      SymbolKind.S_203_1,
      SymbolKind.S_204_2,
      SymbolKind.S_use_type,
      SymbolKind.S_group_use_declaration,
      SymbolKind.S_mixed_group_use_declaration,
      SymbolKind.S_possible_comma,
      SymbolKind.S_inline_use_declarations,
      SymbolKind.S_unprefixed_use_declarations,
      SymbolKind.S_use_declarations,
      SymbolKind.S_inline_use_declaration,
      SymbolKind.S_unprefixed_use_declaration,
      SymbolKind.S_use_declaration,
      SymbolKind.S_const_list,
      SymbolKind.S_inner_statement_list,
      SymbolKind.S_inner_statement,
      SymbolKind.S_statement,
      SymbolKind.S_219_3,
      SymbolKind.S_catch_list,
      SymbolKind.S_catch_name_list,
      SymbolKind.S_optional_variable,
      SymbolKind.S_finally_statement,
      SymbolKind.S_unset_variables,
      SymbolKind.S_unset_variable,
      SymbolKind.S_function_name,
      SymbolKind.S_function_declaration_statement,
      SymbolKind.S_is_reference,
      SymbolKind.S_is_variadic,
      SymbolKind.S_class_declaration_statement,
      SymbolKind.S_231_4,
      SymbolKind.S_232_5,
      SymbolKind.S_class_modifiers,
      SymbolKind.S_anonymous_class_modifiers,
      SymbolKind.S_anonymous_class_modifiers_optional,
      SymbolKind.S_class_modifier,
      SymbolKind.S_trait_declaration_statement,
      SymbolKind.S_238_6,
      SymbolKind.S_interface_declaration_statement,
      SymbolKind.S_240_7,
      SymbolKind.S_enum_declaration_statement,
      SymbolKind.S_242_8,
      SymbolKind.S_enum_backing_type,
      SymbolKind.S_enum_case,
      SymbolKind.S_enum_case_expr,
      SymbolKind.S_extends_from,
      SymbolKind.S_interface_extends_list,
      SymbolKind.S_implements_list,
      SymbolKind.S_foreach_variable,
      SymbolKind.S_for_statement,
      SymbolKind.S_foreach_statement,
      SymbolKind.S_declare_statement,
      SymbolKind.S_switch_case_list,
      SymbolKind.S_case_list,
      SymbolKind.S_match,
      SymbolKind.S_match_arm_list,
      SymbolKind.S_non_empty_match_arm_list,
      SymbolKind.S_match_arm,
      SymbolKind.S_match_arm_cond_list,
      SymbolKind.S_while_statement,
      SymbolKind.S_if_stmt_without_else,
      SymbolKind.S_if_stmt,
      SymbolKind.S_alt_if_stmt_without_else,
      SymbolKind.S_alt_if_stmt,
      SymbolKind.S_parameter_list,
      SymbolKind.S_non_empty_parameter_list,
      SymbolKind.S_attributed_parameter,
      SymbolKind.S_optional_cpp_modifiers,
      SymbolKind.S_parameter,
      SymbolKind.S_optional_type_without_static,
      SymbolKind.S_type_expr,
      SymbolKind.S_type,
      SymbolKind.S_union_type_element,
      SymbolKind.S_union_type,
      SymbolKind.S_intersection_type,
      SymbolKind.S_type_expr_without_static,
      SymbolKind.S_type_without_static,
      SymbolKind.S_union_type_without_static_element,
      SymbolKind.S_union_type_without_static,
      SymbolKind.S_intersection_type_without_static,
      SymbolKind.S_return_type,
      SymbolKind.S_argument_list,
      SymbolKind.S_non_empty_argument_list,
      SymbolKind.S_clone_argument_list,
      SymbolKind.S_non_empty_clone_argument_list,
      SymbolKind.S_argument_no_expr,
      SymbolKind.S_argument,
      SymbolKind.S_global_var_list,
      SymbolKind.S_global_var,
      SymbolKind.S_static_var_list,
      SymbolKind.S_static_var,
      SymbolKind.S_class_statement_list,
      SymbolKind.S_attributed_class_statement,
      SymbolKind.S_class_statement,
      SymbolKind.S_class_name_list,
      SymbolKind.S_trait_adaptations,
      SymbolKind.S_trait_adaptation_list,
      SymbolKind.S_trait_adaptation,
      SymbolKind.S_trait_precedence,
      SymbolKind.S_trait_alias,
      SymbolKind.S_trait_method_reference,
      SymbolKind.S_absolute_trait_method_reference,
      SymbolKind.S_method_body,
      SymbolKind.S_property_modifiers,
      SymbolKind.S_method_modifiers,
      SymbolKind.S_class_const_modifiers,
      SymbolKind.S_non_empty_member_modifiers,
      SymbolKind.S_member_modifier,
      SymbolKind.S_property_list,
      SymbolKind.S_property,
      SymbolKind.S_hooked_property,
      SymbolKind.S_property_hook_list,
      SymbolKind.S_optional_property_hook_list,
      SymbolKind.S_property_hook_modifiers,
      SymbolKind.S_property_hook,
      SymbolKind.S_316_9,
      SymbolKind.S_property_hook_body,
      SymbolKind.S_optional_parameter_list,
      SymbolKind.S_class_const_list,
      SymbolKind.S_class_const_decl,
      SymbolKind.S_const_decl,
      SymbolKind.S_echo_expr_list,
      SymbolKind.S_echo_expr,
      SymbolKind.S_for_cond_exprs,
      SymbolKind.S_for_exprs,
      SymbolKind.S_non_empty_for_exprs,
      SymbolKind.S_anonymous_class,
      SymbolKind.S_328_10,
      SymbolKind.S_new_dereferenceable,
      SymbolKind.S_new_non_dereferenceable,
      SymbolKind.S_expr,
      SymbolKind.S_inline_function,
      SymbolKind.S_fn,
      SymbolKind.S_function,
      SymbolKind.S_backup_doc_comment,
      SymbolKind.S_backup_fn_flags,
      SymbolKind.S_backup_lex_pos,
      SymbolKind.S_returns_ref,
      SymbolKind.S_lexical_vars,
      SymbolKind.S_lexical_var_list,
      SymbolKind.S_lexical_var,
      SymbolKind.S_function_call,
      SymbolKind.S_343_11,
      SymbolKind.S_class_name,
      SymbolKind.S_class_name_reference,
      SymbolKind.S_backticks_expr,
      SymbolKind.S_ctor_arguments,
      SymbolKind.S_dereferenceable_scalar,
      SymbolKind.S_scalar,
      SymbolKind.S_constant,
      SymbolKind.S_class_constant,
      SymbolKind.S_optional_expr,
      SymbolKind.S_variable_class_name,
      SymbolKind.S_fully_dereferenceable,
      SymbolKind.S_array_object_dereferenceable,
      SymbolKind.S_callable_expr,
      SymbolKind.S_callable_variable,
      SymbolKind.S_variable,
      SymbolKind.S_simple_variable,
      SymbolKind.S_static_member,
      SymbolKind.S_new_variable,
      SymbolKind.S_member_name,
      SymbolKind.S_property_name,
      SymbolKind.S_array_pair_list,
      SymbolKind.S_possible_array_pair,
      SymbolKind.S_non_empty_array_pair_list,
      SymbolKind.S_array_pair,
      SymbolKind.S_encaps_list,
      SymbolKind.S_encaps_var,
      SymbolKind.S_encaps_var_offset,
      SymbolKind.S_internal_functions_in_yacc,
      SymbolKind.S_isset_variables,
      SymbolKind.S_isset_variable
    };

    static final SymbolKind get(int code) {
      return values_[code];
    }

    public final int getCode() {
      return this.yycode_;
    }

    /* YYNAMES_[SYMBOL-NUM] -- String name of the symbol SYMBOL-NUM.
       First, the terminals, then, starting at \a YYNTOKENS_, nonterminals.  */
    private static final String[] yynames_ = yynames_init();
  private static final String[] yynames_init()
  {
    return new String[]
    {
  "end of file", "error", "invalid token", "PREC_ARROW_FUNCTION", "'='",
  "'?'", "':'", "'|'", "'^'", "'<'", "'>'", "'.'", "'+'", "'-'", "'*'",
  "'/'", "'%'", "'!'", "'~'", "'@'", "T_NOELSE", "integer",
  "floating-point number", "identifier", "fully qualified name",
  "namespace-relative name", "namespaced name", "variable",
  "T_INLINE_HTML", "string content", "quoted string", "variable name",
  "number", "'include'", "'include_once'", "'eval'", "'require'",
  "'require_once'", "'or'", "'xor'", "'and'", "'print'", "'yield'",
  "'yield from'", "'instanceof'", "'new'", "'clone'", "'exit'", "'if'",
  "'elseif'", "'else'", "'endif'", "'echo'", "'do'", "'while'",
  "'endwhile'", "'for'", "'endfor'", "'foreach'", "'endforeach'",
  "'declare'", "'enddeclare'", "'as'", "'switch'", "'endswitch'", "'case'",
  "'default'", "'match'", "'break'", "'continue'", "'goto'", "'function'",
  "'fn'", "'const'", "'return'", "'try'", "'catch'", "'finally'",
  "'throw'", "'use'", "'insteadof'", "'global'", "'static'", "'abstract'",
  "'final'", "'private'", "'protected'", "'public'", "'private(set)'",
  "'protected(set)'", "'public(set)'", "'readonly'", "'var'", "'unset'",
  "'isset'", "'empty'", "'__halt_compiler'", "'class'", "'trait'",
  "'interface'", "'enum'", "'extends'", "'implements'", "'namespace'",
  "'list'", "'array'", "'callable'", "'__LINE__'", "'__FILE__'",
  "'__DIR__'", "'__CLASS__'", "'__TRAIT__'", "'__METHOD__'",
  "'__FUNCTION__'", "'__PROPERTY__'", "'__NAMESPACE__'", "'#['", "'+='",
  "'-='", "'*='", "'/='", "'.='", "'%='", "'&='", "'|='", "'^='", "'<<='",
  "'>>='", "'??='", "'||'", "'&&'", "'=='", "'!='", "'==='", "'!=='",
  "'<='", "'>='", "'<=>'", "'<<'", "'>>'", "'++'", "'--'", "'(int)'",
  "'(float)'", "'(string)'", "'(array)'", "'(object)'", "'(bool)'",
  "'(unset)'", "'(void)'", "'->'", "'?->'", "'=>'", "comment",
  "doc comment", "open tag", "'<?='", "'?>'", "whitespace",
  "heredoc start", "heredoc end", "'${'", "'{$'", "'::'", "'\\'", "'...'",
  "'??'", "'**'", "'**='", "'|>'", "'&'", "amp", "invalid character",
  "T_ERROR", "','", "']'", "';'", "'('", "')'", "'{'", "'}'", "'['", "'`'",
  "'\"'", "'$'", "$accept", "start", "reserved_non_modifiers",
  "semi_reserved", "ampersand", "identifier", "top_statement_list",
  "namespace_declaration_name", "namespace_name", "legacy_namespace_name",
  "name", "attribute_decl", "attribute_group", "attribute", "attributes",
  "attributed_statement", "attributed_top_statement", "top_statement",
  "$@1", "$@2", "use_type", "group_use_declaration",
  "mixed_group_use_declaration", "possible_comma",
  "inline_use_declarations", "unprefixed_use_declarations",
  "use_declarations", "inline_use_declaration",
  "unprefixed_use_declaration", "use_declaration", "const_list",
  "inner_statement_list", "inner_statement", "statement", "$@3",
  "catch_list", "catch_name_list", "optional_variable",
  "finally_statement", "unset_variables", "unset_variable",
  "function_name", "function_declaration_statement", "is_reference",
  "is_variadic", "class_declaration_statement", "@4", "@5",
  "class_modifiers", "anonymous_class_modifiers",
  "anonymous_class_modifiers_optional", "class_modifier",
  "trait_declaration_statement", "@6", "interface_declaration_statement",
  "@7", "enum_declaration_statement", "@8", "enum_backing_type",
  "enum_case", "enum_case_expr", "extends_from", "interface_extends_list",
  "implements_list", "foreach_variable", "for_statement",
  "foreach_statement", "declare_statement", "switch_case_list",
  "case_list", "match", "match_arm_list", "non_empty_match_arm_list",
  "match_arm", "match_arm_cond_list", "while_statement",
  "if_stmt_without_else", "if_stmt", "alt_if_stmt_without_else",
  "alt_if_stmt", "parameter_list", "non_empty_parameter_list",
  "attributed_parameter", "optional_cpp_modifiers", "parameter",
  "optional_type_without_static", "type_expr", "type",
  "union_type_element", "union_type", "intersection_type",
  "type_expr_without_static", "type_without_static",
  "union_type_without_static_element", "union_type_without_static",
  "intersection_type_without_static", "return_type", "argument_list",
  "non_empty_argument_list", "clone_argument_list",
  "non_empty_clone_argument_list", "argument_no_expr", "argument",
  "global_var_list", "global_var", "static_var_list", "static_var",
  "class_statement_list", "attributed_class_statement", "class_statement",
  "class_name_list", "trait_adaptations", "trait_adaptation_list",
  "trait_adaptation", "trait_precedence", "trait_alias",
  "trait_method_reference", "absolute_trait_method_reference",
  "method_body", "property_modifiers", "method_modifiers",
  "class_const_modifiers", "non_empty_member_modifiers", "member_modifier",
  "property_list", "property", "hooked_property", "property_hook_list",
  "optional_property_hook_list", "property_hook_modifiers",
  "property_hook", "@9", "property_hook_body", "optional_parameter_list",
  "class_const_list", "class_const_decl", "const_decl", "echo_expr_list",
  "echo_expr", "for_cond_exprs", "for_exprs", "non_empty_for_exprs",
  "anonymous_class", "@10", "new_dereferenceable",
  "new_non_dereferenceable", "expr", "inline_function", "fn", "function",
  "backup_doc_comment", "backup_fn_flags", "backup_lex_pos", "returns_ref",
  "lexical_vars", "lexical_var_list", "lexical_var", "function_call",
  "@11", "class_name", "class_name_reference", "backticks_expr",
  "ctor_arguments", "dereferenceable_scalar", "scalar", "constant",
  "class_constant", "optional_expr", "variable_class_name",
  "fully_dereferenceable", "array_object_dereferenceable", "callable_expr",
  "callable_variable", "variable", "simple_variable", "static_member",
  "new_variable", "member_name", "property_name", "array_pair_list",
  "possible_array_pair", "non_empty_array_pair_list", "array_pair",
  "encaps_list", "encaps_var", "encaps_var_offset",
  "internal_functions_in_yacc", "isset_variables", "isset_variable", null
    };
  }

    /* The user-facing name of this symbol.  */
    public final String getName() {
      return yynames_[yycode_];
    }
  };


  /**
   * Communication interface between the scanner and the Bison-generated
   * parser <tt>ZendLanguageParser</tt>.
   */
  public interface Lexer {
    /* Token kinds.  */
    /** Token "end of file", to be returned by the scanner.  */
    static final int END = 0;
    /** Token error, to be returned by the scanner.  */
    static final int YYerror = 256;
    /** Token "invalid token", to be returned by the scanner.  */
    static final int YYUNDEF = 257;
    /** Token PREC_ARROW_FUNCTION, to be returned by the scanner.  */
    static final int PREC_ARROW_FUNCTION = 258;
    /** Token T_NOELSE, to be returned by the scanner.  */
    static final int T_NOELSE = 259;
    /** Token "integer", to be returned by the scanner.  */
    static final int T_LNUMBER = 260;
    /** Token "floating-point number", to be returned by the scanner.  */
    static final int T_DNUMBER = 261;
    /** Token "identifier", to be returned by the scanner.  */
    static final int T_STRING = 262;
    /** Token "fully qualified name", to be returned by the scanner.  */
    static final int T_NAME_FULLY_QUALIFIED = 263;
    /** Token "namespace-relative name", to be returned by the scanner.  */
    static final int T_NAME_RELATIVE = 264;
    /** Token "namespaced name", to be returned by the scanner.  */
    static final int T_NAME_QUALIFIED = 265;
    /** Token "variable", to be returned by the scanner.  */
    static final int T_VARIABLE = 266;
    /** Token T_INLINE_HTML, to be returned by the scanner.  */
    static final int T_INLINE_HTML = 267;
    /** Token "string content", to be returned by the scanner.  */
    static final int T_ENCAPSED_AND_WHITESPACE = 268;
    /** Token "quoted string", to be returned by the scanner.  */
    static final int T_CONSTANT_ENCAPSED_STRING = 269;
    /** Token "variable name", to be returned by the scanner.  */
    static final int T_STRING_VARNAME = 270;
    /** Token "number", to be returned by the scanner.  */
    static final int T_NUM_STRING = 271;
    /** Token "'include'", to be returned by the scanner.  */
    static final int T_INCLUDE = 272;
    /** Token "'include_once'", to be returned by the scanner.  */
    static final int T_INCLUDE_ONCE = 273;
    /** Token "'eval'", to be returned by the scanner.  */
    static final int T_EVAL = 274;
    /** Token "'require'", to be returned by the scanner.  */
    static final int T_REQUIRE = 275;
    /** Token "'require_once'", to be returned by the scanner.  */
    static final int T_REQUIRE_ONCE = 276;
    /** Token "'or'", to be returned by the scanner.  */
    static final int T_LOGICAL_OR = 277;
    /** Token "'xor'", to be returned by the scanner.  */
    static final int T_LOGICAL_XOR = 278;
    /** Token "'and'", to be returned by the scanner.  */
    static final int T_LOGICAL_AND = 279;
    /** Token "'print'", to be returned by the scanner.  */
    static final int T_PRINT = 280;
    /** Token "'yield'", to be returned by the scanner.  */
    static final int T_YIELD = 281;
    /** Token "'yield from'", to be returned by the scanner.  */
    static final int T_YIELD_FROM = 282;
    /** Token "'instanceof'", to be returned by the scanner.  */
    static final int T_INSTANCEOF = 283;
    /** Token "'new'", to be returned by the scanner.  */
    static final int T_NEW = 284;
    /** Token "'clone'", to be returned by the scanner.  */
    static final int T_CLONE = 285;
    /** Token "'exit'", to be returned by the scanner.  */
    static final int T_EXIT = 286;
    /** Token "'if'", to be returned by the scanner.  */
    static final int T_IF = 287;
    /** Token "'elseif'", to be returned by the scanner.  */
    static final int T_ELSEIF = 288;
    /** Token "'else'", to be returned by the scanner.  */
    static final int T_ELSE = 289;
    /** Token "'endif'", to be returned by the scanner.  */
    static final int T_ENDIF = 290;
    /** Token "'echo'", to be returned by the scanner.  */
    static final int T_ECHO = 291;
    /** Token "'do'", to be returned by the scanner.  */
    static final int T_DO = 292;
    /** Token "'while'", to be returned by the scanner.  */
    static final int T_WHILE = 293;
    /** Token "'endwhile'", to be returned by the scanner.  */
    static final int T_ENDWHILE = 294;
    /** Token "'for'", to be returned by the scanner.  */
    static final int T_FOR = 295;
    /** Token "'endfor'", to be returned by the scanner.  */
    static final int T_ENDFOR = 296;
    /** Token "'foreach'", to be returned by the scanner.  */
    static final int T_FOREACH = 297;
    /** Token "'endforeach'", to be returned by the scanner.  */
    static final int T_ENDFOREACH = 298;
    /** Token "'declare'", to be returned by the scanner.  */
    static final int T_DECLARE = 299;
    /** Token "'enddeclare'", to be returned by the scanner.  */
    static final int T_ENDDECLARE = 300;
    /** Token "'as'", to be returned by the scanner.  */
    static final int T_AS = 301;
    /** Token "'switch'", to be returned by the scanner.  */
    static final int T_SWITCH = 302;
    /** Token "'endswitch'", to be returned by the scanner.  */
    static final int T_ENDSWITCH = 303;
    /** Token "'case'", to be returned by the scanner.  */
    static final int T_CASE = 304;
    /** Token "'default'", to be returned by the scanner.  */
    static final int T_DEFAULT = 305;
    /** Token "'match'", to be returned by the scanner.  */
    static final int T_MATCH = 306;
    /** Token "'break'", to be returned by the scanner.  */
    static final int T_BREAK = 307;
    /** Token "'continue'", to be returned by the scanner.  */
    static final int T_CONTINUE = 308;
    /** Token "'goto'", to be returned by the scanner.  */
    static final int T_GOTO = 309;
    /** Token "'function'", to be returned by the scanner.  */
    static final int T_FUNCTION = 310;
    /** Token "'fn'", to be returned by the scanner.  */
    static final int T_FN = 311;
    /** Token "'const'", to be returned by the scanner.  */
    static final int T_CONST = 312;
    /** Token "'return'", to be returned by the scanner.  */
    static final int T_RETURN = 313;
    /** Token "'try'", to be returned by the scanner.  */
    static final int T_TRY = 314;
    /** Token "'catch'", to be returned by the scanner.  */
    static final int T_CATCH = 315;
    /** Token "'finally'", to be returned by the scanner.  */
    static final int T_FINALLY = 316;
    /** Token "'throw'", to be returned by the scanner.  */
    static final int T_THROW = 317;
    /** Token "'use'", to be returned by the scanner.  */
    static final int T_USE = 318;
    /** Token "'insteadof'", to be returned by the scanner.  */
    static final int T_INSTEADOF = 319;
    /** Token "'global'", to be returned by the scanner.  */
    static final int T_GLOBAL = 320;
    /** Token "'static'", to be returned by the scanner.  */
    static final int T_STATIC = 321;
    /** Token "'abstract'", to be returned by the scanner.  */
    static final int T_ABSTRACT = 322;
    /** Token "'final'", to be returned by the scanner.  */
    static final int T_FINAL = 323;
    /** Token "'private'", to be returned by the scanner.  */
    static final int T_PRIVATE = 324;
    /** Token "'protected'", to be returned by the scanner.  */
    static final int T_PROTECTED = 325;
    /** Token "'public'", to be returned by the scanner.  */
    static final int T_PUBLIC = 326;
    /** Token "'private(set)'", to be returned by the scanner.  */
    static final int T_PRIVATE_SET = 327;
    /** Token "'protected(set)'", to be returned by the scanner.  */
    static final int T_PROTECTED_SET = 328;
    /** Token "'public(set)'", to be returned by the scanner.  */
    static final int T_PUBLIC_SET = 329;
    /** Token "'readonly'", to be returned by the scanner.  */
    static final int T_READONLY = 330;
    /** Token "'var'", to be returned by the scanner.  */
    static final int T_VAR = 331;
    /** Token "'unset'", to be returned by the scanner.  */
    static final int T_UNSET = 332;
    /** Token "'isset'", to be returned by the scanner.  */
    static final int T_ISSET = 333;
    /** Token "'empty'", to be returned by the scanner.  */
    static final int T_EMPTY = 334;
    /** Token "'__halt_compiler'", to be returned by the scanner.  */
    static final int T_HALT_COMPILER = 335;
    /** Token "'class'", to be returned by the scanner.  */
    static final int T_CLASS = 336;
    /** Token "'trait'", to be returned by the scanner.  */
    static final int T_TRAIT = 337;
    /** Token "'interface'", to be returned by the scanner.  */
    static final int T_INTERFACE = 338;
    /** Token "'enum'", to be returned by the scanner.  */
    static final int T_ENUM = 339;
    /** Token "'extends'", to be returned by the scanner.  */
    static final int T_EXTENDS = 340;
    /** Token "'implements'", to be returned by the scanner.  */
    static final int T_IMPLEMENTS = 341;
    /** Token "'namespace'", to be returned by the scanner.  */
    static final int T_NAMESPACE = 342;
    /** Token "'list'", to be returned by the scanner.  */
    static final int T_LIST = 343;
    /** Token "'array'", to be returned by the scanner.  */
    static final int T_ARRAY = 344;
    /** Token "'callable'", to be returned by the scanner.  */
    static final int T_CALLABLE = 345;
    /** Token "'__LINE__'", to be returned by the scanner.  */
    static final int T_LINE = 346;
    /** Token "'__FILE__'", to be returned by the scanner.  */
    static final int T_FILE = 347;
    /** Token "'__DIR__'", to be returned by the scanner.  */
    static final int T_DIR = 348;
    /** Token "'__CLASS__'", to be returned by the scanner.  */
    static final int T_CLASS_C = 349;
    /** Token "'__TRAIT__'", to be returned by the scanner.  */
    static final int T_TRAIT_C = 350;
    /** Token "'__METHOD__'", to be returned by the scanner.  */
    static final int T_METHOD_C = 351;
    /** Token "'__FUNCTION__'", to be returned by the scanner.  */
    static final int T_FUNC_C = 352;
    /** Token "'__PROPERTY__'", to be returned by the scanner.  */
    static final int T_PROPERTY_C = 353;
    /** Token "'__NAMESPACE__'", to be returned by the scanner.  */
    static final int T_NS_C = 354;
    /** Token "'#['", to be returned by the scanner.  */
    static final int T_ATTRIBUTE = 355;
    /** Token "'+='", to be returned by the scanner.  */
    static final int T_PLUS_EQUAL = 356;
    /** Token "'-='", to be returned by the scanner.  */
    static final int T_MINUS_EQUAL = 357;
    /** Token "'*='", to be returned by the scanner.  */
    static final int T_MUL_EQUAL = 358;
    /** Token "'/='", to be returned by the scanner.  */
    static final int T_DIV_EQUAL = 359;
    /** Token "'.='", to be returned by the scanner.  */
    static final int T_CONCAT_EQUAL = 360;
    /** Token "'%='", to be returned by the scanner.  */
    static final int T_MOD_EQUAL = 361;
    /** Token "'&='", to be returned by the scanner.  */
    static final int T_AND_EQUAL = 362;
    /** Token "'|='", to be returned by the scanner.  */
    static final int T_OR_EQUAL = 363;
    /** Token "'^='", to be returned by the scanner.  */
    static final int T_XOR_EQUAL = 364;
    /** Token "'<<='", to be returned by the scanner.  */
    static final int T_SL_EQUAL = 365;
    /** Token "'>>='", to be returned by the scanner.  */
    static final int T_SR_EQUAL = 366;
    /** Token "'??='", to be returned by the scanner.  */
    static final int T_COALESCE_EQUAL = 367;
    /** Token "'||'", to be returned by the scanner.  */
    static final int T_BOOLEAN_OR = 368;
    /** Token "'&&'", to be returned by the scanner.  */
    static final int T_BOOLEAN_AND = 369;
    /** Token "'=='", to be returned by the scanner.  */
    static final int T_IS_EQUAL = 370;
    /** Token "'!='", to be returned by the scanner.  */
    static final int T_IS_NOT_EQUAL = 371;
    /** Token "'==='", to be returned by the scanner.  */
    static final int T_IS_IDENTICAL = 372;
    /** Token "'!=='", to be returned by the scanner.  */
    static final int T_IS_NOT_IDENTICAL = 373;
    /** Token "'<='", to be returned by the scanner.  */
    static final int T_IS_SMALLER_OR_EQUAL = 374;
    /** Token "'>='", to be returned by the scanner.  */
    static final int T_IS_GREATER_OR_EQUAL = 375;
    /** Token "'<=>'", to be returned by the scanner.  */
    static final int T_SPACESHIP = 376;
    /** Token "'<<'", to be returned by the scanner.  */
    static final int T_SL = 377;
    /** Token "'>>'", to be returned by the scanner.  */
    static final int T_SR = 378;
    /** Token "'++'", to be returned by the scanner.  */
    static final int T_INC = 379;
    /** Token "'--'", to be returned by the scanner.  */
    static final int T_DEC = 380;
    /** Token "'(int)'", to be returned by the scanner.  */
    static final int T_INT_CAST = 381;
    /** Token "'(float)'", to be returned by the scanner.  */
    static final int T_DOUBLE_CAST = 382;
    /** Token "'(string)'", to be returned by the scanner.  */
    static final int T_STRING_CAST = 383;
    /** Token "'(array)'", to be returned by the scanner.  */
    static final int T_ARRAY_CAST = 384;
    /** Token "'(object)'", to be returned by the scanner.  */
    static final int T_OBJECT_CAST = 385;
    /** Token "'(bool)'", to be returned by the scanner.  */
    static final int T_BOOL_CAST = 386;
    /** Token "'(unset)'", to be returned by the scanner.  */
    static final int T_UNSET_CAST = 387;
    /** Token "'(void)'", to be returned by the scanner.  */
    static final int T_VOID_CAST = 388;
    /** Token "'->'", to be returned by the scanner.  */
    static final int T_OBJECT_OPERATOR = 389;
    /** Token "'?->'", to be returned by the scanner.  */
    static final int T_NULLSAFE_OBJECT_OPERATOR = 390;
    /** Token "'=>'", to be returned by the scanner.  */
    static final int T_DOUBLE_ARROW = 391;
    /** Token "comment", to be returned by the scanner.  */
    static final int T_COMMENT = 392;
    /** Token "doc comment", to be returned by the scanner.  */
    static final int T_DOC_COMMENT = 393;
    /** Token "open tag", to be returned by the scanner.  */
    static final int T_OPEN_TAG = 394;
    /** Token "'<?='", to be returned by the scanner.  */
    static final int T_OPEN_TAG_WITH_ECHO = 395;
    /** Token "'?>'", to be returned by the scanner.  */
    static final int T_CLOSE_TAG = 396;
    /** Token "whitespace", to be returned by the scanner.  */
    static final int T_WHITESPACE = 397;
    /** Token "heredoc start", to be returned by the scanner.  */
    static final int T_START_HEREDOC = 398;
    /** Token "heredoc end", to be returned by the scanner.  */
    static final int T_END_HEREDOC = 399;
    /** Token "'${'", to be returned by the scanner.  */
    static final int T_DOLLAR_OPEN_CURLY_BRACES = 400;
    /** Token "'{$'", to be returned by the scanner.  */
    static final int T_CURLY_OPEN = 401;
    /** Token "'::'", to be returned by the scanner.  */
    static final int T_PAAMAYIM_NEKUDOTAYIM = 402;
    /** Token "'\\'", to be returned by the scanner.  */
    static final int T_NS_SEPARATOR = 403;
    /** Token "'...'", to be returned by the scanner.  */
    static final int T_ELLIPSIS = 404;
    /** Token "'??'", to be returned by the scanner.  */
    static final int T_COALESCE = 405;
    /** Token "'**'", to be returned by the scanner.  */
    static final int T_POW = 406;
    /** Token "'**='", to be returned by the scanner.  */
    static final int T_POW_EQUAL = 407;
    /** Token "'|>'", to be returned by the scanner.  */
    static final int T_PIPE = 408;
    /** Token "'&'", to be returned by the scanner.  */
    static final int T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG = 409;
    /** Token "amp", to be returned by the scanner.  */
    static final int T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG = 410;
    /** Token "invalid character", to be returned by the scanner.  */
    static final int T_BAD_CHARACTER = 411;
    /** Token T_ERROR, to be returned by the scanner.  */
    static final int T_ERROR = 412;

    /** Deprecated, use END instead.  */
    public static final int EOF = END;


    /**
     * Method to retrieve the semantic value of the last scanned token.
     * @return the semantic value of the last scanned token.
     */
    Object getLVal();

    /**
     * Entry point for the scanner.  Returns the token identifier corresponding
     * to the next token and prepares to return the semantic value
     * of the token.
     * @return the token identifier corresponding to the next token.
     */
    int yylex() throws java.io.IOException;

    /**
     * Emit an errorin a user-defined way.
     *
     *
     * @param msg The string for the error message.
     */
     void yyerror(String msg);


    /**
     * Build and emit a "syntax error" message in a user-defined way.
     *
     * @param ctx  The context of the error.
     */
     void reportSyntaxError(Context ctx);

  }


  /**
   * The object doing lexical analysis for us.
   */
  private Lexer yylexer;





  /**
   * Instantiates the Bison-generated parser.
   * @param yylexer The scanner that will supply tokens to the parser.
   */
  public ZendLanguageParser(Lexer yylexer)
  {
/* "%code init" blocks.  */
/* "src/main/grammar/zend_language_parser.y":38  */

    bind(yylexer);

/* "src/main/java/pl/codeloom/php/parser/zend/generated/ZendLanguageParser.java":1330  */

    this.yylexer = yylexer;

  }



  private int yynerrs = 0;

  /**
   * The number of syntax errors so far.
   */
  public final int getNumberOfErrors() { return yynerrs; }

  /**
   * Print an error message via the lexer.
   *
   * @param msg The error message.
   */
  public final void yyerror(String msg) {
      yylexer.yyerror(msg);
  }



  private final class YYStack {
    private int[] stateStack = new int[16];
    private Object[] valueStack = new Object[16];

    public int size = 16;
    public int height = -1;

    public final void push(int state, Object value) {
      height++;
      // the C skeleton's bound (post_bison.py)
      if (height + 1 >= YYMAXDEPTH)
        throw new MemoryExhausted();
      if (size == height) {
        int[] newStateStack = new int[size * 2];
        System.arraycopy(stateStack, 0, newStateStack, 0, height);
        stateStack = newStateStack;

        Object[] newValueStack = new Object[size * 2];
        System.arraycopy(valueStack, 0, newValueStack, 0, height);
        valueStack = newValueStack;

        size *= 2;
      }

      stateStack[height] = state;
      valueStack[height] = value;
    }

    public final void pop() {
      pop(1);
    }

    public final void pop(int num) {
      // Avoid memory leaks... garbage collection is a white lie!
      if (0 < num) {
        java.util.Arrays.fill(valueStack, height - num + 1, height + 1, null);
      }
      height -= num;
    }

    public final int stateAt(int i) {
      return stateStack[height - i];
    }

    public final Object valueAt(int i) {
      return valueStack[height - i];
    }

    // Print the state stack on the debug stream.
    public void print(java.io.PrintStream out) {
      out.print ("Stack now");

      for (int i = 0; i <= height; i++) {
        out.print(' ');
        out.print(stateStack[i]);
      }
      out.println();
    }
  }

  /**
   * Returned by a Bison action in order to stop the parsing process and
   * return success (<tt>true</tt>).
   */
  public static final int YYACCEPT = 0;

  /**
   * Returned by a Bison action in order to stop the parsing process and
   * return failure (<tt>false</tt>).
   */
  public static final int YYABORT = 1;



  /**
   * Returned by a Bison action in order to start error recovery without
   * printing an error message.
   */
  public static final int YYERROR = 2;

  /**
   * Internal return codes that are not supported for user semantic
   * actions.
   */
  private static final int YYERRLAB = 3;
  private static final int YYNEWSTATE = 4;
  private static final int YYDEFAULT = 5;
  private static final int YYREDUCE = 6;
  private static final int YYERRLAB1 = 7;
  private static final int YYRETURN = 8;


  private int yyerrstatus_ = 0;


  /**
   * Whether error recovery is being done.  In this state, the parser
   * reads token until it reaches a known state, and then restarts normal
   * operation.
   */
  public final boolean recovering ()
  {
    return yyerrstatus_ == 0;
  }

  /** Compute post-reduction state.
   * @param yystate   the current state
   * @param yysym     the nonterminal to push on the stack
   */
  private int yyLRGotoState(int yystate, int yysym) {
    int yyr = yypgoto_[yysym - YYNTOKENS_] + yystate;
    if (0 <= yyr && yyr <= YYLAST_ && yycheck_[yyr] == yystate)
      return yytable_[yyr];
    else
      return yydefgoto_[yysym - YYNTOKENS_];
  }

  private int yyaction(int yyn, YYStack yystack, int yylen)
  {
    /* If YYLEN is nonzero, implement the default value of the action:
       '$$ = $1'.  Otherwise, use the top of the stack.

       Otherwise, the following line sets YYVAL to garbage.
       This behavior is undocumented and Bison
       users should not rely upon it.  */
    Object yyval = (0 < yylen) ? yystack.valueAt(yylen - 1) : yystack.valueAt(0);

    // the actions are in methods of 120 (split by tools/parser/post_bison.py)
    this.yyval = yyval;
    int yystatus = switch (YYACTION_PART[yyn])
      {
        case 0 -> yyaction_0(yyn, yystack);
        case 1 -> yyaction_1(yyn, yystack);
        case 2 -> yyaction_2(yyn, yystack);
        case 3 -> yyaction_3(yyn, yystack);
        case 4 -> yyaction_4(yyn, yystack);
        default -> YYNEWSTATE;
      };
    if (yystatus != YYNEWSTATE)
      return yystatus;
    yyval = this.yyval;


    yystack.pop(yylen);
    yylen = 0;
    /* Shift the result of the reduction.  */
    int yystate = yyLRGotoState(yystack.stateAt(0), yyr1_[yyn]);
    yystack.push(yystate, yyval);
    return YYNEWSTATE;
  }

  /** $$ between yyaction and the method with the rule's action. */
  private Object yyval;

  /** The method (yyaction_N) with each rule's action, -1 for a rule without one. */
  private static final byte[] YYACTION_PART = yyaction_part_init();

  private static byte[] yyaction_part_init()
  {
    byte[] part = new byte[637];
    java.util.Arrays.fill(part, (byte) -1);
    for (int rule : new int[] {2, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204})
      part[rule] = 0;
    for (int rule : new int[] {205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287, 288, 289, 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321, 322, 323, 324})
      part[rule] = 1;
    for (int rule : new int[] {325, 326, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 356, 357, 358, 359, 360, 361, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384, 385, 386, 387, 388, 389, 390, 391, 392, 393, 394, 395, 396, 397, 398, 399, 400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 418, 419, 420, 421, 422, 423, 424, 425, 426, 427, 428, 429, 430, 431, 432, 433, 434, 435, 436, 437, 438, 439, 440, 441, 442, 443, 444})
      part[rule] = 2;
    for (int rule : new int[] {445, 446, 447, 448, 449, 450, 451, 452, 453, 454, 455, 456, 457, 458, 459, 460, 461, 462, 463, 464, 465, 466, 467, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477, 478, 479, 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 490, 491, 492, 493, 494, 495, 496, 497, 498, 499, 500, 501, 502, 503, 504, 505, 506, 507, 508, 509, 510, 511, 512, 513, 514, 515, 516, 517, 518, 519, 520, 521, 522, 523, 524, 525, 526, 527, 528, 529, 530, 531, 532, 533, 534, 535, 536, 537, 538, 539, 540, 541, 542, 543, 544, 545, 546, 547, 548, 549, 550, 551, 552, 553, 554, 555, 556, 557, 558, 559, 560, 561, 562, 563, 564})
      part[rule] = 3;
    for (int rule : new int[] {565, 566, 567, 568, 569, 570, 571, 572, 573, 574, 575, 576, 577, 578, 579, 580, 581, 582, 583, 584, 585, 586, 587, 588, 589, 590, 591, 592, 593, 594, 595, 596, 597, 598, 599, 600, 601, 602, 603, 604, 605, 606, 607, 608, 609, 610, 611, 612, 613, 614, 615, 616, 617, 618, 619, 620, 621, 622, 623, 624, 625, 626, 627, 628, 629, 630, 631, 632, 633, 634, 635})
      part[rule] = 4;
    return part;
  }

  private int yyaction_0(int yyn, YYStack yystack)
  {
    Object yyval = this.yyval;
    switch (yyn)
      {
          case 2: /* start: top_statement_list  */
  if (yyn == 2)
    /* "src/main/grammar/zend_language_parser.y":304  */
                                { CG.ast = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 84: /* identifier: "identifier"  */
  if (yyn == 84)
    /* "src/main/grammar/zend_language_parser.y":329  */
                         { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 85: /* identifier: semi_reserved  */
  if (yyn == 85)
    /* "src/main/grammar/zend_language_parser.y":330  */
                               {
			zval zv = new zval();
			if (zend_lex_tstring(zv, ((Integer)(yystack.valueAt (0)))) == FAILURE) { return YYABORT; }
			yyval = zend_ast_create_zval(zv);
		};
  break;


  case 86: /* top_statement_list: top_statement_list top_statement  */
  if (yyn == 86)
    /* "src/main/grammar/zend_language_parser.y":338  */
                                                 { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 87: /* top_statement_list: %empty  */
  if (yyn == 87)
    /* "src/main/grammar/zend_language_parser.y":339  */
                       { yyval = zend_ast_create_list(0, ZEND_AST_STMT_LIST); };
  break;


  case 88: /* namespace_declaration_name: identifier  */
  if (yyn == 88)
    /* "src/main/grammar/zend_language_parser.y":344  */
                                                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 89: /* namespace_declaration_name: "namespaced name"  */
  if (yyn == 89)
    /* "src/main/grammar/zend_language_parser.y":345  */
                                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 90: /* namespace_name: "identifier"  */
  if (yyn == 90)
    /* "src/main/grammar/zend_language_parser.y":350  */
                                                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 91: /* namespace_name: "namespaced name"  */
  if (yyn == 91)
    /* "src/main/grammar/zend_language_parser.y":351  */
                                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 92: /* legacy_namespace_name: namespace_name  */
  if (yyn == 92)
    /* "src/main/grammar/zend_language_parser.y":356  */
                                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 93: /* legacy_namespace_name: "fully qualified name"  */
  if (yyn == 93)
    /* "src/main/grammar/zend_language_parser.y":357  */
                                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 94: /* name: "identifier"  */
  if (yyn == 94)
    /* "src/main/grammar/zend_language_parser.y":361  */
                                                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr = ZEND_NAME_NOT_FQ; };
  break;


  case 95: /* name: "namespaced name"  */
  if (yyn == 95)
    /* "src/main/grammar/zend_language_parser.y":362  */
                                                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr = ZEND_NAME_NOT_FQ; };
  break;


  case 96: /* name: "fully qualified name"  */
  if (yyn == 96)
    /* "src/main/grammar/zend_language_parser.y":363  */
                                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr = ZEND_NAME_FQ; };
  break;


  case 97: /* name: "namespace-relative name"  */
  if (yyn == 97)
    /* "src/main/grammar/zend_language_parser.y":364  */
                                                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr = ZEND_NAME_RELATIVE; };
  break;


  case 98: /* attribute_decl: class_name  */
  if (yyn == 98)
    /* "src/main/grammar/zend_language_parser.y":369  */
                        { yyval = zend_ast_create(ZEND_AST_ATTRIBUTE, ((ZendAst)(yystack.valueAt (0))), null); };
  break;


  case 99: /* attribute_decl: class_name argument_list  */
  if (yyn == 99)
    /* "src/main/grammar/zend_language_parser.y":371  */
                        { yyval = zend_ast_create(ZEND_AST_ATTRIBUTE, ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 100: /* attribute_group: attribute_decl  */
  if (yyn == 100)
    /* "src/main/grammar/zend_language_parser.y":376  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_ATTRIBUTE_GROUP, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 101: /* attribute_group: attribute_group ',' attribute_decl  */
  if (yyn == 101)
    /* "src/main/grammar/zend_language_parser.y":378  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 102: /* attribute: "'#['" backup_doc_comment attribute_group possible_comma ']'  */
  if (yyn == 102)
    /* "src/main/grammar/zend_language_parser.y":382  */
                                                                                        { yyval = ((ZendAst)(yystack.valueAt (2))); CG.doc_comment = ((byte[])(yystack.valueAt (3))); };
  break;


  case 103: /* attributes: attribute  */
  if (yyn == 103)
    /* "src/main/grammar/zend_language_parser.y":386  */
                                                        { yyval = zend_ast_create_list(1, ZEND_AST_ATTRIBUTE_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 104: /* attributes: attributes attribute  */
  if (yyn == 104)
    /* "src/main/grammar/zend_language_parser.y":387  */
                                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 105: /* attributed_statement: function_declaration_statement  */
  if (yyn == 105)
    /* "src/main/grammar/zend_language_parser.y":391  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 106: /* attributed_statement: class_declaration_statement  */
  if (yyn == 106)
    /* "src/main/grammar/zend_language_parser.y":392  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 107: /* attributed_statement: trait_declaration_statement  */
  if (yyn == 107)
    /* "src/main/grammar/zend_language_parser.y":393  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 108: /* attributed_statement: interface_declaration_statement  */
  if (yyn == 108)
    /* "src/main/grammar/zend_language_parser.y":394  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 109: /* attributed_statement: enum_declaration_statement  */
  if (yyn == 109)
    /* "src/main/grammar/zend_language_parser.y":395  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 110: /* attributed_top_statement: attributed_statement  */
  if (yyn == 110)
    /* "src/main/grammar/zend_language_parser.y":399  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 111: /* attributed_top_statement: "'const'" const_list ';'  */
  if (yyn == 111)
    /* "src/main/grammar/zend_language_parser.y":400  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 112: /* top_statement: statement  */
  if (yyn == 112)
    /* "src/main/grammar/zend_language_parser.y":404  */
                                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 113: /* top_statement: attributed_top_statement  */
  if (yyn == 113)
    /* "src/main/grammar/zend_language_parser.y":405  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 114: /* top_statement: attributes attributed_top_statement  */
  if (yyn == 114)
    /* "src/main/grammar/zend_language_parser.y":406  */
                                                        { yyval = zend_ast_with_attributes(((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 115: /* top_statement: "'__halt_compiler'" '(' ')' ';'  */
  if (yyn == 115)
    /* "src/main/grammar/zend_language_parser.y":408  */
                        { yyval = zend_ast_create(ZEND_AST_HALT_COMPILER,
			      zend_ast_create_zval_from_long(zend_get_scanned_file_offset()));
			  zend_stop_lexing(); };
  break;


  case 116: /* top_statement: "'namespace'" namespace_declaration_name ';'  */
  if (yyn == 116)
    /* "src/main/grammar/zend_language_parser.y":412  */
                        { yyval = zend_ast_create(ZEND_AST_NAMESPACE, ((ZendAst)(yystack.valueAt (1))), null);
			  RESET_DOC_COMMENT(); };
  break;


  case 117: /* $@1: %empty  */
  if (yyn == 117)
    /* "src/main/grammar/zend_language_parser.y":414  */
                                                       { RESET_DOC_COMMENT(); };
  break;


  case 118: /* top_statement: "'namespace'" namespace_declaration_name $@1 '{' top_statement_list '}'  */
  if (yyn == 118)
    /* "src/main/grammar/zend_language_parser.y":416  */
                        { yyval = zend_ast_create(ZEND_AST_NAMESPACE, ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 119: /* $@2: %empty  */
  if (yyn == 119)
    /* "src/main/grammar/zend_language_parser.y":417  */
                            { RESET_DOC_COMMENT(); };
  break;


  case 120: /* top_statement: "'namespace'" $@2 '{' top_statement_list '}'  */
  if (yyn == 120)
    /* "src/main/grammar/zend_language_parser.y":419  */
                        { yyval = zend_ast_create(ZEND_AST_NAMESPACE, null, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 121: /* top_statement: "'use'" mixed_group_use_declaration ';'  */
  if (yyn == 121)
    /* "src/main/grammar/zend_language_parser.y":420  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 122: /* top_statement: "'use'" use_type group_use_declaration ';'  */
  if (yyn == 122)
    /* "src/main/grammar/zend_language_parser.y":421  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (1))); ((ZendAst) yyval).attr = ((Integer)(yystack.valueAt (2))); };
  break;


  case 123: /* top_statement: "'use'" use_declarations ';'  */
  if (yyn == 123)
    /* "src/main/grammar/zend_language_parser.y":422  */
                                                                                { yyval = ((ZendAst)(yystack.valueAt (1))); ((ZendAst) yyval).attr = ZEND_SYMBOL_CLASS; };
  break;


  case 124: /* top_statement: "'use'" use_type use_declarations ';'  */
  if (yyn == 124)
    /* "src/main/grammar/zend_language_parser.y":423  */
                                                                        { yyval = ((ZendAst)(yystack.valueAt (1))); ((ZendAst) yyval).attr = ((Integer)(yystack.valueAt (2))); };
  break;


  case 125: /* use_type: "'function'"  */
  if (yyn == 125)
    /* "src/main/grammar/zend_language_parser.y":427  */
                                        { yyval = ZEND_SYMBOL_FUNCTION; };
  break;


  case 126: /* use_type: "'const'"  */
  if (yyn == 126)
    /* "src/main/grammar/zend_language_parser.y":428  */
                                        { yyval = ZEND_SYMBOL_CONST; };
  break;


  case 127: /* group_use_declaration: legacy_namespace_name "'\\'" '{' unprefixed_use_declarations possible_comma '}'  */
  if (yyn == 127)
    /* "src/main/grammar/zend_language_parser.y":433  */
                        { yyval = zend_ast_create(ZEND_AST_GROUP_USE, ((ZendAst)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (2)))); };
  break;


  case 128: /* mixed_group_use_declaration: legacy_namespace_name "'\\'" '{' inline_use_declarations possible_comma '}'  */
  if (yyn == 128)
    /* "src/main/grammar/zend_language_parser.y":438  */
                        { yyval = zend_ast_create(ZEND_AST_GROUP_USE, ((ZendAst)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (2))));};
  break;


  case 131: /* inline_use_declarations: inline_use_declarations ',' inline_use_declaration  */
  if (yyn == 131)
    /* "src/main/grammar/zend_language_parser.y":448  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 132: /* inline_use_declarations: inline_use_declaration  */
  if (yyn == 132)
    /* "src/main/grammar/zend_language_parser.y":450  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_USE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 133: /* unprefixed_use_declarations: unprefixed_use_declarations ',' unprefixed_use_declaration  */
  if (yyn == 133)
    /* "src/main/grammar/zend_language_parser.y":455  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 134: /* unprefixed_use_declarations: unprefixed_use_declaration  */
  if (yyn == 134)
    /* "src/main/grammar/zend_language_parser.y":457  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_USE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 135: /* use_declarations: use_declarations ',' use_declaration  */
  if (yyn == 135)
    /* "src/main/grammar/zend_language_parser.y":462  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 136: /* use_declarations: use_declaration  */
  if (yyn == 136)
    /* "src/main/grammar/zend_language_parser.y":464  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_USE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 137: /* inline_use_declaration: unprefixed_use_declaration  */
  if (yyn == 137)
    /* "src/main/grammar/zend_language_parser.y":468  */
                                           { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr = ZEND_SYMBOL_CLASS; };
  break;


  case 138: /* inline_use_declaration: use_type unprefixed_use_declaration  */
  if (yyn == 138)
    /* "src/main/grammar/zend_language_parser.y":469  */
                                                    { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr = ((Integer)(yystack.valueAt (1))); };
  break;


  case 139: /* unprefixed_use_declaration: namespace_name  */
  if (yyn == 139)
    /* "src/main/grammar/zend_language_parser.y":474  */
                        { yyval = zend_ast_create(ZEND_AST_USE_ELEM, ((ZendAst)(yystack.valueAt (0))), null); };
  break;


  case 140: /* unprefixed_use_declaration: namespace_name "'as'" "identifier"  */
  if (yyn == 140)
    /* "src/main/grammar/zend_language_parser.y":476  */
                        { yyval = zend_ast_create(ZEND_AST_USE_ELEM, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 141: /* use_declaration: legacy_namespace_name  */
  if (yyn == 141)
    /* "src/main/grammar/zend_language_parser.y":481  */
                        { yyval = zend_ast_create(ZEND_AST_USE_ELEM, ((ZendAst)(yystack.valueAt (0))), null); };
  break;


  case 142: /* use_declaration: legacy_namespace_name "'as'" "identifier"  */
  if (yyn == 142)
    /* "src/main/grammar/zend_language_parser.y":483  */
                        { yyval = zend_ast_create(ZEND_AST_USE_ELEM, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 143: /* const_list: const_list ',' const_decl  */
  if (yyn == 143)
    /* "src/main/grammar/zend_language_parser.y":487  */
                                          { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 144: /* const_list: const_decl  */
  if (yyn == 144)
    /* "src/main/grammar/zend_language_parser.y":488  */
                           { yyval = zend_ast_create_list(1, ZEND_AST_CONST_DECL, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 145: /* inner_statement_list: inner_statement_list inner_statement  */
  if (yyn == 145)
    /* "src/main/grammar/zend_language_parser.y":493  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 146: /* inner_statement_list: %empty  */
  if (yyn == 146)
    /* "src/main/grammar/zend_language_parser.y":495  */
                        { yyval = zend_ast_create_list(0, ZEND_AST_STMT_LIST); };
  break;


  case 147: /* inner_statement: statement  */
  if (yyn == 147)
    /* "src/main/grammar/zend_language_parser.y":500  */
                          { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 148: /* inner_statement: attributed_statement  */
  if (yyn == 148)
    /* "src/main/grammar/zend_language_parser.y":501  */
                                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 149: /* inner_statement: attributes attributed_statement  */
  if (yyn == 149)
    /* "src/main/grammar/zend_language_parser.y":502  */
                                                        { yyval = zend_ast_with_attributes(((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 150: /* inner_statement: "'__halt_compiler'" '(' ')' ';'  */
  if (yyn == 150)
    /* "src/main/grammar/zend_language_parser.y":504  */
                        { yyval = null; zend_throw_exception(zend_ce_compile_error,
			      "__HALT_COMPILER() can only be used from the outermost scope", 0); return YYERROR; };
  break;


  case 151: /* statement: '{' inner_statement_list '}'  */
  if (yyn == 151)
    /* "src/main/grammar/zend_language_parser.y":510  */
                                             { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 152: /* statement: if_stmt  */
  if (yyn == 152)
    /* "src/main/grammar/zend_language_parser.y":511  */
                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 153: /* statement: alt_if_stmt  */
  if (yyn == 153)
    /* "src/main/grammar/zend_language_parser.y":512  */
                            { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 154: /* statement: "'while'" '(' expr ')' while_statement  */
  if (yyn == 154)
    /* "src/main/grammar/zend_language_parser.y":514  */
                        { yyval = zend_ast_create(ZEND_AST_WHILE, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 155: /* statement: "'do'" statement "'while'" '(' expr ')' ';'  */
  if (yyn == 155)
    /* "src/main/grammar/zend_language_parser.y":516  */
                        { yyval = zend_ast_create(ZEND_AST_DO_WHILE, ((ZendAst)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (2)))); };
  break;


  case 156: /* statement: "'for'" '(' for_exprs ';' for_cond_exprs ';' for_exprs ')' for_statement  */
  if (yyn == 156)
    /* "src/main/grammar/zend_language_parser.y":518  */
                        { yyval = zend_ast_create(ZEND_AST_FOR, ((ZendAst)(yystack.valueAt (6))), ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 157: /* statement: "'switch'" '(' expr ')' switch_case_list  */
  if (yyn == 157)
    /* "src/main/grammar/zend_language_parser.y":520  */
                        { yyval = zend_ast_create(ZEND_AST_SWITCH, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 158: /* statement: "'break'" optional_expr ';'  */
  if (yyn == 158)
    /* "src/main/grammar/zend_language_parser.y":521  */
                                                        { yyval = zend_ast_create(ZEND_AST_BREAK, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 159: /* statement: "'continue'" optional_expr ';'  */
  if (yyn == 159)
    /* "src/main/grammar/zend_language_parser.y":522  */
                                                { yyval = zend_ast_create(ZEND_AST_CONTINUE, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 160: /* statement: "'return'" optional_expr ';'  */
  if (yyn == 160)
    /* "src/main/grammar/zend_language_parser.y":523  */
                                                        { yyval = zend_ast_create(ZEND_AST_RETURN, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 161: /* statement: "'global'" global_var_list ';'  */
  if (yyn == 161)
    /* "src/main/grammar/zend_language_parser.y":524  */
                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 162: /* statement: "'static'" static_var_list ';'  */
  if (yyn == 162)
    /* "src/main/grammar/zend_language_parser.y":525  */
                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 163: /* statement: "'echo'" echo_expr_list ';'  */
  if (yyn == 163)
    /* "src/main/grammar/zend_language_parser.y":526  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 164: /* statement: T_INLINE_HTML  */
  if (yyn == 164)
    /* "src/main/grammar/zend_language_parser.y":527  */
                              { yyval = zend_ast_create(ZEND_AST_ECHO, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 165: /* statement: expr ';'  */
  if (yyn == 165)
    /* "src/main/grammar/zend_language_parser.y":528  */
                         { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 166: /* statement: "'unset'" '(' unset_variables possible_comma ')' ';'  */
  if (yyn == 166)
    /* "src/main/grammar/zend_language_parser.y":529  */
                                                                   { yyval = ((ZendAst)(yystack.valueAt (3))); };
  break;


  case 167: /* statement: "'foreach'" '(' expr "'as'" foreach_variable ')' foreach_statement  */
  if (yyn == 167)
    /* "src/main/grammar/zend_language_parser.y":531  */
                        { yyval = zend_ast_create(ZEND_AST_FOREACH, ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (2))), null, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 168: /* statement: "'foreach'" '(' expr "'as'" foreach_variable "'=>'" foreach_variable ')' foreach_statement  */
  if (yyn == 168)
    /* "src/main/grammar/zend_language_parser.y":534  */
                        { yyval = zend_ast_create(ZEND_AST_FOREACH, ((ZendAst)(yystack.valueAt (6))), ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 169: /* $@3: %empty  */
  if (yyn == 169)
    /* "src/main/grammar/zend_language_parser.y":536  */
                        { if (!zend_handle_encoding_declaration(((ZendAst)(yystack.valueAt (1))))) { return YYERROR; } };
  break;


  case 170: /* statement: "'declare'" '(' const_list ')' $@3 declare_statement  */
  if (yyn == 170)
    /* "src/main/grammar/zend_language_parser.y":538  */
                        { yyval = zend_ast_create(ZEND_AST_DECLARE, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 171: /* statement: ';'  */
  if (yyn == 171)
    /* "src/main/grammar/zend_language_parser.y":539  */
                                              { yyval = null; };
  break;


  case 172: /* statement: "'try'" '{' inner_statement_list '}' catch_list finally_statement  */
  if (yyn == 172)
    /* "src/main/grammar/zend_language_parser.y":541  */
                        { yyval = zend_ast_create(ZEND_AST_TRY, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 173: /* statement: "'goto'" "identifier" ';'  */
  if (yyn == 173)
    /* "src/main/grammar/zend_language_parser.y":542  */
                                    { yyval = zend_ast_create(ZEND_AST_GOTO, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 174: /* statement: "identifier" ':'  */
  if (yyn == 174)
    /* "src/main/grammar/zend_language_parser.y":543  */
                             { yyval = zend_ast_create(ZEND_AST_LABEL, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 175: /* statement: "'(void)'" expr ';'  */
  if (yyn == 175)
    /* "src/main/grammar/zend_language_parser.y":544  */
                                     { yyval = zend_ast_create(ZEND_AST_CAST_VOID, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 176: /* catch_list: %empty  */
  if (yyn == 176)
    /* "src/main/grammar/zend_language_parser.y":549  */
                        { yyval = zend_ast_create_list(0, ZEND_AST_CATCH_LIST); };
  break;


  case 177: /* catch_list: catch_list "'catch'" '(' catch_name_list optional_variable ')' '{' inner_statement_list '}'  */
  if (yyn == 177)
    /* "src/main/grammar/zend_language_parser.y":551  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (8))), zend_ast_create(ZEND_AST_CATCH, ((ZendAst)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1))))); };
  break;


  case 178: /* catch_name_list: class_name  */
  if (yyn == 178)
    /* "src/main/grammar/zend_language_parser.y":555  */
                           { yyval = zend_ast_create_list(1, ZEND_AST_NAME_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 179: /* catch_name_list: catch_name_list '|' class_name  */
  if (yyn == 179)
    /* "src/main/grammar/zend_language_parser.y":556  */
                                               { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 180: /* optional_variable: %empty  */
  if (yyn == 180)
    /* "src/main/grammar/zend_language_parser.y":560  */
                       { yyval = null; };
  break;


  case 181: /* optional_variable: "variable"  */
  if (yyn == 181)
    /* "src/main/grammar/zend_language_parser.y":561  */
                           { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 182: /* finally_statement: %empty  */
  if (yyn == 182)
    /* "src/main/grammar/zend_language_parser.y":565  */
                       { yyval = null; };
  break;


  case 183: /* finally_statement: "'finally'" '{' inner_statement_list '}'  */
  if (yyn == 183)
    /* "src/main/grammar/zend_language_parser.y":566  */
                                                       { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 184: /* unset_variables: unset_variable  */
  if (yyn == 184)
    /* "src/main/grammar/zend_language_parser.y":570  */
                               { yyval = zend_ast_create_list(1, ZEND_AST_STMT_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 185: /* unset_variables: unset_variables ',' unset_variable  */
  if (yyn == 185)
    /* "src/main/grammar/zend_language_parser.y":571  */
                                                   { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 186: /* unset_variable: variable  */
  if (yyn == 186)
    /* "src/main/grammar/zend_language_parser.y":575  */
                         { yyval = zend_ast_create(ZEND_AST_UNSET, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 187: /* function_name: "identifier"  */
  if (yyn == 187)
    /* "src/main/grammar/zend_language_parser.y":579  */
                         { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 188: /* function_name: "'readonly'"  */
  if (yyn == 188)
    /* "src/main/grammar/zend_language_parser.y":580  */
                           {
			zval zv = new zval();
			if (zend_lex_tstring(zv, ((Integer)(yystack.valueAt (0)))) == FAILURE) { return YYABORT; }
			yyval = zend_ast_create_zval(zv);
		};
  break;


  case 189: /* function_declaration_statement: function returns_ref function_name backup_doc_comment '(' parameter_list ')' return_type backup_fn_flags '{' inner_statement_list '}' backup_fn_flags  */
  if (yyn == 189)
    /* "src/main/grammar/zend_language_parser.y":590  */
                { yyval = zend_ast_create_decl(ZEND_AST_FUNC_DECL, ((Integer)(yystack.valueAt (11))) | ((Integer)(yystack.valueAt (0))), ((Integer)(yystack.valueAt (12))), ((byte[])(yystack.valueAt (9))),
		      zend_ast_get_str(((ZendAst)(yystack.valueAt (10)))), ((ZendAst)(yystack.valueAt (7))), null, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (5))), null); CG.extra_fn_flags = ((Integer)(yystack.valueAt (4))); };
  break;


  case 190: /* is_reference: %empty  */
  if (yyn == 190)
    /* "src/main/grammar/zend_language_parser.y":595  */
                        { yyval = 0; };
  break;


  case 191: /* is_reference: "'&'"  */
  if (yyn == 191)
    /* "src/main/grammar/zend_language_parser.y":596  */
                                                        { yyval = ZEND_PARAM_REF; };
  break;


  case 192: /* is_variadic: %empty  */
  if (yyn == 192)
    /* "src/main/grammar/zend_language_parser.y":600  */
                       { yyval = 0; };
  break;


  case 193: /* is_variadic: "'...'"  */
  if (yyn == 193)
    /* "src/main/grammar/zend_language_parser.y":601  */
                            { yyval = ZEND_PARAM_VARIADIC; };
  break;


  case 194: /* @4: %empty  */
  if (yyn == 194)
    /* "src/main/grammar/zend_language_parser.y":605  */
                                        { yyval = CG.zend_lineno; };
  break;


  case 195: /* class_declaration_statement: class_modifiers "'class'" @4 "identifier" extends_from implements_list backup_doc_comment '{' class_statement_list '}'  */
  if (yyn == 195)
    /* "src/main/grammar/zend_language_parser.y":607  */
                        { yyval = zend_ast_create_decl(ZEND_AST_CLASS, ((Integer)(yystack.valueAt (9))), ((Integer)(yystack.valueAt (7))), ((byte[])(yystack.valueAt (3))), zend_ast_get_str(((ZendAst)(yystack.valueAt (6)))), ((ZendAst)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1))), null, null); };
  break;


  case 196: /* @5: %empty  */
  if (yyn == 196)
    /* "src/main/grammar/zend_language_parser.y":608  */
                        { yyval = CG.zend_lineno; };
  break;


  case 197: /* class_declaration_statement: "'class'" @5 "identifier" extends_from implements_list backup_doc_comment '{' class_statement_list '}'  */
  if (yyn == 197)
    /* "src/main/grammar/zend_language_parser.y":610  */
                        { yyval = zend_ast_create_decl(ZEND_AST_CLASS, 0, ((Integer)(yystack.valueAt (7))), ((byte[])(yystack.valueAt (3))), zend_ast_get_str(((ZendAst)(yystack.valueAt (6)))), ((ZendAst)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1))), null, null); };
  break;


  case 198: /* class_modifiers: class_modifier  */
  if (yyn == 198)
    /* "src/main/grammar/zend_language_parser.y":614  */
                                                                { yyval = ((Integer)(yystack.valueAt (0))); };
  break;


  case 199: /* class_modifiers: class_modifiers class_modifier  */
  if (yyn == 199)
    /* "src/main/grammar/zend_language_parser.y":616  */
                        { yyval = zend_add_class_modifier(((Integer)(yystack.valueAt (1))), ((Integer)(yystack.valueAt (0)))); if ((((Integer) yyval) == 0)) { return YYERROR; } };
  break;


  case 200: /* anonymous_class_modifiers: class_modifier  */
  if (yyn == 200)
    /* "src/main/grammar/zend_language_parser.y":621  */
                        { yyval = zend_add_anonymous_class_modifier(0, ((Integer)(yystack.valueAt (0)))); if ((((Integer) yyval) == 0)) { return YYERROR; } };
  break;


  case 201: /* anonymous_class_modifiers: anonymous_class_modifiers class_modifier  */
  if (yyn == 201)
    /* "src/main/grammar/zend_language_parser.y":623  */
                        { yyval = zend_add_anonymous_class_modifier(((Integer)(yystack.valueAt (1))), ((Integer)(yystack.valueAt (0)))); if ((((Integer) yyval) == 0)) { return YYERROR; } };
  break;


  case 202: /* anonymous_class_modifiers_optional: %empty  */
  if (yyn == 202)
    /* "src/main/grammar/zend_language_parser.y":627  */
                                                { yyval = 0; };
  break;


  case 203: /* anonymous_class_modifiers_optional: anonymous_class_modifiers  */
  if (yyn == 203)
    /* "src/main/grammar/zend_language_parser.y":628  */
                                                { yyval = ((Integer)(yystack.valueAt (0))); };
  break;


  case 204: /* class_modifier: "'abstract'"  */
  if (yyn == 204)
    /* "src/main/grammar/zend_language_parser.y":632  */
                                        { yyval = ZEND_ACC_EXPLICIT_ABSTRACT_CLASS; };
  break;


        default: break;
      }
    this.yyval = yyval;
    return YYNEWSTATE;
  }

  private int yyaction_1(int yyn, YYStack yystack)
  {
    Object yyval = this.yyval;
    switch (yyn)
      {
  case 205: /* class_modifier: "'final'"  */
  if (yyn == 205)
    /* "src/main/grammar/zend_language_parser.y":633  */
                                        { yyval = ZEND_ACC_FINAL; };
  break;


  case 206: /* class_modifier: "'readonly'"  */
  if (yyn == 206)
    /* "src/main/grammar/zend_language_parser.y":634  */
                                        { yyval = ZEND_ACC_READONLY_CLASS|ZEND_ACC_NO_DYNAMIC_PROPERTIES; };
  break;


  case 207: /* @6: %empty  */
  if (yyn == 207)
    /* "src/main/grammar/zend_language_parser.y":638  */
                        { yyval = CG.zend_lineno; };
  break;


  case 208: /* trait_declaration_statement: "'trait'" @6 "identifier" backup_doc_comment '{' class_statement_list '}'  */
  if (yyn == 208)
    /* "src/main/grammar/zend_language_parser.y":640  */
                        { yyval = zend_ast_create_decl(ZEND_AST_CLASS, ZEND_ACC_TRAIT, ((Integer)(yystack.valueAt (5))), ((byte[])(yystack.valueAt (3))), zend_ast_get_str(((ZendAst)(yystack.valueAt (4)))), null, null, ((ZendAst)(yystack.valueAt (1))), null, null); };
  break;


  case 209: /* @7: %empty  */
  if (yyn == 209)
    /* "src/main/grammar/zend_language_parser.y":644  */
                            { yyval = CG.zend_lineno; };
  break;


  case 210: /* interface_declaration_statement: "'interface'" @7 "identifier" interface_extends_list backup_doc_comment '{' class_statement_list '}'  */
  if (yyn == 210)
    /* "src/main/grammar/zend_language_parser.y":646  */
                        { yyval = zend_ast_create_decl(ZEND_AST_CLASS, ZEND_ACC_INTERFACE, ((Integer)(yystack.valueAt (6))), ((byte[])(yystack.valueAt (3))), zend_ast_get_str(((ZendAst)(yystack.valueAt (5)))), null, ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1))), null, null); };
  break;


  case 211: /* @8: %empty  */
  if (yyn == 211)
    /* "src/main/grammar/zend_language_parser.y":650  */
                       { yyval = CG.zend_lineno; };
  break;


  case 212: /* enum_declaration_statement: "'enum'" @8 "identifier" enum_backing_type implements_list backup_doc_comment '{' class_statement_list '}'  */
  if (yyn == 212)
    /* "src/main/grammar/zend_language_parser.y":652  */
                        { yyval = zend_ast_create_decl(ZEND_AST_CLASS, ZEND_ACC_ENUM|ZEND_ACC_FINAL, ((Integer)(yystack.valueAt (7))), ((byte[])(yystack.valueAt (3))), zend_ast_get_str(((ZendAst)(yystack.valueAt (6)))), null, ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1))), null, ((ZendAst)(yystack.valueAt (5)))); };
  break;


  case 213: /* enum_backing_type: %empty  */
  if (yyn == 213)
    /* "src/main/grammar/zend_language_parser.y":656  */
                        { yyval = null; };
  break;


  case 214: /* enum_backing_type: ':' type_expr  */
  if (yyn == 214)
    /* "src/main/grammar/zend_language_parser.y":657  */
                              { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 215: /* enum_case: "'case'" backup_doc_comment identifier enum_case_expr ';'  */
  if (yyn == 215)
    /* "src/main/grammar/zend_language_parser.y":662  */
                        { yyval = zend_ast_create(ZEND_AST_ENUM_CASE, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (1))), ((((byte[])(yystack.valueAt (3))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (3)))) : null), null); };
  break;


  case 216: /* enum_case_expr: %empty  */
  if (yyn == 216)
    /* "src/main/grammar/zend_language_parser.y":666  */
                        { yyval = null; };
  break;


  case 217: /* enum_case_expr: '=' expr  */
  if (yyn == 217)
    /* "src/main/grammar/zend_language_parser.y":667  */
                         { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 218: /* extends_from: %empty  */
  if (yyn == 218)
    /* "src/main/grammar/zend_language_parser.y":671  */
                                                { yyval = null; };
  break;


  case 219: /* extends_from: "'extends'" class_name  */
  if (yyn == 219)
    /* "src/main/grammar/zend_language_parser.y":672  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 220: /* interface_extends_list: %empty  */
  if (yyn == 220)
    /* "src/main/grammar/zend_language_parser.y":676  */
                                                { yyval = null; };
  break;


  case 221: /* interface_extends_list: "'extends'" class_name_list  */
  if (yyn == 221)
    /* "src/main/grammar/zend_language_parser.y":677  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 222: /* implements_list: %empty  */
  if (yyn == 222)
    /* "src/main/grammar/zend_language_parser.y":681  */
                                                        { yyval = null; };
  break;


  case 223: /* implements_list: "'implements'" class_name_list  */
  if (yyn == 223)
    /* "src/main/grammar/zend_language_parser.y":682  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 224: /* foreach_variable: variable  */
  if (yyn == 224)
    /* "src/main/grammar/zend_language_parser.y":686  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 225: /* foreach_variable: ampersand variable  */
  if (yyn == 225)
    /* "src/main/grammar/zend_language_parser.y":687  */
                                        { yyval = zend_ast_create(ZEND_AST_REF, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 226: /* foreach_variable: "'list'" '(' array_pair_list ')'  */
  if (yyn == 226)
    /* "src/main/grammar/zend_language_parser.y":688  */
                                               { yyval = ((ZendAst)(yystack.valueAt (1))); ((ZendAst) yyval).attr = ZEND_ARRAY_SYNTAX_LIST; };
  break;


  case 227: /* foreach_variable: '[' array_pair_list ']'  */
  if (yyn == 227)
    /* "src/main/grammar/zend_language_parser.y":689  */
                                        { yyval = ((ZendAst)(yystack.valueAt (1))); ((ZendAst) yyval).attr = ZEND_ARRAY_SYNTAX_SHORT; };
  break;


  case 228: /* for_statement: statement  */
  if (yyn == 228)
    /* "src/main/grammar/zend_language_parser.y":693  */
                          { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 229: /* for_statement: ':' inner_statement_list "'endfor'" ';'  */
  if (yyn == 229)
    /* "src/main/grammar/zend_language_parser.y":694  */
                                                      { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 230: /* foreach_statement: statement  */
  if (yyn == 230)
    /* "src/main/grammar/zend_language_parser.y":698  */
                          { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 231: /* foreach_statement: ':' inner_statement_list "'endforeach'" ';'  */
  if (yyn == 231)
    /* "src/main/grammar/zend_language_parser.y":699  */
                                                          { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 232: /* declare_statement: statement  */
  if (yyn == 232)
    /* "src/main/grammar/zend_language_parser.y":703  */
                          { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 233: /* declare_statement: ':' inner_statement_list "'enddeclare'" ';'  */
  if (yyn == 233)
    /* "src/main/grammar/zend_language_parser.y":704  */
                                                          { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 234: /* switch_case_list: '{' case_list '}'  */
  if (yyn == 234)
    /* "src/main/grammar/zend_language_parser.y":708  */
                                                                        { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 235: /* switch_case_list: '{' ';' case_list '}'  */
  if (yyn == 235)
    /* "src/main/grammar/zend_language_parser.y":709  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 236: /* switch_case_list: ':' case_list "'endswitch'" ';'  */
  if (yyn == 236)
    /* "src/main/grammar/zend_language_parser.y":710  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 237: /* switch_case_list: ':' ';' case_list "'endswitch'" ';'  */
  if (yyn == 237)
    /* "src/main/grammar/zend_language_parser.y":711  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 238: /* case_list: %empty  */
  if (yyn == 238)
    /* "src/main/grammar/zend_language_parser.y":715  */
                       { yyval = zend_ast_create_list(0, ZEND_AST_SWITCH_LIST); };
  break;


  case 239: /* case_list: case_list "'case'" expr ':' inner_statement_list  */
  if (yyn == 239)
    /* "src/main/grammar/zend_language_parser.y":717  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (4))), zend_ast_create(ZEND_AST_SWITCH_CASE, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 240: /* case_list: case_list "'case'" expr ';' inner_statement_list  */
  if (yyn == 240)
    /* "src/main/grammar/zend_language_parser.y":719  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (4))), zend_ast_create_ex(ZEND_AST_SWITCH_CASE, ZEND_ALT_CASE_SYNTAX, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 241: /* case_list: case_list "'default'" ':' inner_statement_list  */
  if (yyn == 241)
    /* "src/main/grammar/zend_language_parser.y":721  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (3))), zend_ast_create(ZEND_AST_SWITCH_CASE, null, ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 242: /* case_list: case_list "'default'" ';' inner_statement_list  */
  if (yyn == 242)
    /* "src/main/grammar/zend_language_parser.y":723  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (3))), zend_ast_create_ex(ZEND_AST_SWITCH_CASE, ZEND_ALT_CASE_SYNTAX, null, ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 243: /* match: "'match'" '(' expr ')' '{' match_arm_list '}'  */
  if (yyn == 243)
    /* "src/main/grammar/zend_language_parser.y":729  */
                        { yyval = zend_ast_create(ZEND_AST_MATCH, ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 244: /* match_arm_list: %empty  */
  if (yyn == 244)
    /* "src/main/grammar/zend_language_parser.y":733  */
                       { yyval = zend_ast_create_list(0, ZEND_AST_MATCH_ARM_LIST); };
  break;


  case 245: /* match_arm_list: non_empty_match_arm_list possible_comma  */
  if (yyn == 245)
    /* "src/main/grammar/zend_language_parser.y":734  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 246: /* non_empty_match_arm_list: match_arm  */
  if (yyn == 246)
    /* "src/main/grammar/zend_language_parser.y":738  */
                          { yyval = zend_ast_create_list(1, ZEND_AST_MATCH_ARM_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 247: /* non_empty_match_arm_list: non_empty_match_arm_list ',' match_arm  */
  if (yyn == 247)
    /* "src/main/grammar/zend_language_parser.y":739  */
                                                       { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 248: /* match_arm: match_arm_cond_list possible_comma "'=>'" expr  */
  if (yyn == 248)
    /* "src/main/grammar/zend_language_parser.y":744  */
                        { yyval = zend_ast_create(ZEND_AST_MATCH_ARM, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 249: /* match_arm: "'default'" possible_comma "'=>'" expr  */
  if (yyn == 249)
    /* "src/main/grammar/zend_language_parser.y":746  */
                        { yyval = zend_ast_create(ZEND_AST_MATCH_ARM, null, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 250: /* match_arm_cond_list: expr  */
  if (yyn == 250)
    /* "src/main/grammar/zend_language_parser.y":750  */
                     { yyval = zend_ast_create_list(1, ZEND_AST_EXPR_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 251: /* match_arm_cond_list: match_arm_cond_list ',' expr  */
  if (yyn == 251)
    /* "src/main/grammar/zend_language_parser.y":751  */
                                             { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 252: /* while_statement: statement  */
  if (yyn == 252)
    /* "src/main/grammar/zend_language_parser.y":756  */
                          { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 253: /* while_statement: ':' inner_statement_list "'endwhile'" ';'  */
  if (yyn == 253)
    /* "src/main/grammar/zend_language_parser.y":757  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 254: /* if_stmt_without_else: "'if'" '(' expr ')' statement  */
  if (yyn == 254)
    /* "src/main/grammar/zend_language_parser.y":763  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_IF,
			      zend_ast_create(ZEND_AST_IF_ELEM, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 255: /* if_stmt_without_else: if_stmt_without_else "'elseif'" '(' expr ')' statement  */
  if (yyn == 255)
    /* "src/main/grammar/zend_language_parser.y":766  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (5))),
			      zend_ast_create(ZEND_AST_IF_ELEM, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 256: /* if_stmt: if_stmt_without_else  */
  if (yyn == 256)
    /* "src/main/grammar/zend_language_parser.y":771  */
                                                    { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 257: /* if_stmt: if_stmt_without_else "'else'" statement  */
  if (yyn == 257)
    /* "src/main/grammar/zend_language_parser.y":773  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), zend_ast_create(ZEND_AST_IF_ELEM, null, ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 258: /* alt_if_stmt_without_else: "'if'" '(' expr ')' ':' inner_statement_list  */
  if (yyn == 258)
    /* "src/main/grammar/zend_language_parser.y":778  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_IF,
			      zend_ast_create(ZEND_AST_IF_ELEM, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 259: /* alt_if_stmt_without_else: alt_if_stmt_without_else "'elseif'" '(' expr ')' ':' inner_statement_list  */
  if (yyn == 259)
    /* "src/main/grammar/zend_language_parser.y":781  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (6))),
			      zend_ast_create(ZEND_AST_IF_ELEM, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 260: /* alt_if_stmt: alt_if_stmt_without_else "'endif'" ';'  */
  if (yyn == 260)
    /* "src/main/grammar/zend_language_parser.y":786  */
                                                     { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 261: /* alt_if_stmt: alt_if_stmt_without_else "'else'" ':' inner_statement_list "'endif'" ';'  */
  if (yyn == 261)
    /* "src/main/grammar/zend_language_parser.y":788  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (5))),
			      zend_ast_create(ZEND_AST_IF_ELEM, null, ((ZendAst)(yystack.valueAt (2))))); };
  break;


  case 262: /* parameter_list: non_empty_parameter_list possible_comma  */
  if (yyn == 262)
    /* "src/main/grammar/zend_language_parser.y":793  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 263: /* parameter_list: %empty  */
  if (yyn == 263)
    /* "src/main/grammar/zend_language_parser.y":794  */
                        { yyval = zend_ast_create_list(0, ZEND_AST_PARAM_LIST); };
  break;


  case 264: /* non_empty_parameter_list: attributed_parameter  */
  if (yyn == 264)
    /* "src/main/grammar/zend_language_parser.y":800  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_PARAM_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 265: /* non_empty_parameter_list: non_empty_parameter_list ',' attributed_parameter  */
  if (yyn == 265)
    /* "src/main/grammar/zend_language_parser.y":802  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 266: /* attributed_parameter: attributes parameter  */
  if (yyn == 266)
    /* "src/main/grammar/zend_language_parser.y":806  */
                                        { yyval = zend_ast_with_attributes(((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 267: /* attributed_parameter: parameter  */
  if (yyn == 267)
    /* "src/main/grammar/zend_language_parser.y":807  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 268: /* optional_cpp_modifiers: %empty  */
  if (yyn == 268)
    /* "src/main/grammar/zend_language_parser.y":812  */
                        { yyval = 0; };
  break;


  case 269: /* optional_cpp_modifiers: non_empty_member_modifiers  */
  if (yyn == 269)
    /* "src/main/grammar/zend_language_parser.y":814  */
                        { yyval = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_CPP, ((ZendAst)(yystack.valueAt (0))));
			  if ((((Integer) yyval) == 0)) { return YYERROR; } };
  break;


  case 270: /* parameter: optional_cpp_modifiers optional_type_without_static is_reference is_variadic "variable" backup_doc_comment optional_property_hook_list  */
  if (yyn == 270)
    /* "src/main/grammar/zend_language_parser.y":821  */
                        { yyval = zend_ast_create_ex(ZEND_AST_PARAM, ((Integer)(yystack.valueAt (6))) | ((Integer)(yystack.valueAt (4))) | ((Integer)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (2))), null,
					null, (((byte[])(yystack.valueAt (1))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (1)))) : null, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 271: /* parameter: optional_cpp_modifiers optional_type_without_static is_reference is_variadic "variable" backup_doc_comment '=' expr optional_property_hook_list  */
  if (yyn == 271)
    /* "src/main/grammar/zend_language_parser.y":825  */
                        { yyval = zend_ast_create_ex(ZEND_AST_PARAM, ((Integer)(yystack.valueAt (8))) | ((Integer)(yystack.valueAt (6))) | ((Integer)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (7))), ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1))),
					null, (((byte[])(yystack.valueAt (3))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (3)))) : null, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 272: /* optional_type_without_static: %empty  */
  if (yyn == 272)
    /* "src/main/grammar/zend_language_parser.y":830  */
                        { yyval = null; };
  break;


  case 273: /* optional_type_without_static: type_expr_without_static  */
  if (yyn == 273)
    /* "src/main/grammar/zend_language_parser.y":831  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 274: /* type_expr: type  */
  if (yyn == 274)
    /* "src/main/grammar/zend_language_parser.y":835  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 275: /* type_expr: '?' type  */
  if (yyn == 275)
    /* "src/main/grammar/zend_language_parser.y":836  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr |= ZEND_TYPE_NULLABLE; };
  break;


  case 276: /* type_expr: union_type  */
  if (yyn == 276)
    /* "src/main/grammar/zend_language_parser.y":837  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 277: /* type_expr: intersection_type  */
  if (yyn == 277)
    /* "src/main/grammar/zend_language_parser.y":838  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 278: /* type: type_without_static  */
  if (yyn == 278)
    /* "src/main/grammar/zend_language_parser.y":842  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 279: /* type: "'static'"  */
  if (yyn == 279)
    /* "src/main/grammar/zend_language_parser.y":843  */
                                                { yyval = zend_ast_create_ex(ZEND_AST_TYPE, IS_STATIC); };
  break;


  case 280: /* union_type_element: type  */
  if (yyn == 280)
    /* "src/main/grammar/zend_language_parser.y":847  */
                     { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 281: /* union_type_element: '(' intersection_type ')'  */
  if (yyn == 281)
    /* "src/main/grammar/zend_language_parser.y":848  */
                                           { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 282: /* union_type: union_type_element '|' union_type_element  */
  if (yyn == 282)
    /* "src/main/grammar/zend_language_parser.y":853  */
                        { yyval = zend_ast_create_list(2, ZEND_AST_TYPE_UNION, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 283: /* union_type: union_type '|' union_type_element  */
  if (yyn == 283)
    /* "src/main/grammar/zend_language_parser.y":855  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 284: /* intersection_type: type "amp" type  */
  if (yyn == 284)
    /* "src/main/grammar/zend_language_parser.y":859  */
                                                                          { yyval = zend_ast_create_list(2, ZEND_AST_TYPE_INTERSECTION, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 285: /* intersection_type: intersection_type "amp" type  */
  if (yyn == 285)
    /* "src/main/grammar/zend_language_parser.y":860  */
                                                                                 { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 286: /* type_expr_without_static: type_without_static  */
  if (yyn == 286)
    /* "src/main/grammar/zend_language_parser.y":867  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 287: /* type_expr_without_static: '?' type_without_static  */
  if (yyn == 287)
    /* "src/main/grammar/zend_language_parser.y":868  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr |= ZEND_TYPE_NULLABLE; };
  break;


  case 288: /* type_expr_without_static: union_type_without_static  */
  if (yyn == 288)
    /* "src/main/grammar/zend_language_parser.y":869  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 289: /* type_expr_without_static: intersection_type_without_static  */
  if (yyn == 289)
    /* "src/main/grammar/zend_language_parser.y":870  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 290: /* type_without_static: "'array'"  */
  if (yyn == 290)
    /* "src/main/grammar/zend_language_parser.y":874  */
                                { yyval = zend_ast_create_ex(ZEND_AST_TYPE, IS_ARRAY); };
  break;


  case 291: /* type_without_static: "'callable'"  */
  if (yyn == 291)
    /* "src/main/grammar/zend_language_parser.y":875  */
                                { yyval = zend_ast_create_ex(ZEND_AST_TYPE, IS_CALLABLE); };
  break;


  case 292: /* type_without_static: name  */
  if (yyn == 292)
    /* "src/main/grammar/zend_language_parser.y":876  */
                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 293: /* union_type_without_static_element: type_without_static  */
  if (yyn == 293)
    /* "src/main/grammar/zend_language_parser.y":880  */
                                    { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 294: /* union_type_without_static_element: '(' intersection_type_without_static ')'  */
  if (yyn == 294)
    /* "src/main/grammar/zend_language_parser.y":881  */
                                                          { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 295: /* union_type_without_static: union_type_without_static_element '|' union_type_without_static_element  */
  if (yyn == 295)
    /* "src/main/grammar/zend_language_parser.y":886  */
                        { yyval = zend_ast_create_list(2, ZEND_AST_TYPE_UNION, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 296: /* union_type_without_static: union_type_without_static '|' union_type_without_static_element  */
  if (yyn == 296)
    /* "src/main/grammar/zend_language_parser.y":888  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 297: /* intersection_type_without_static: type_without_static "amp" type_without_static  */
  if (yyn == 297)
    /* "src/main/grammar/zend_language_parser.y":893  */
                        { yyval = zend_ast_create_list(2, ZEND_AST_TYPE_INTERSECTION, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 298: /* intersection_type_without_static: intersection_type_without_static "amp" type_without_static  */
  if (yyn == 298)
    /* "src/main/grammar/zend_language_parser.y":895  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 299: /* return_type: %empty  */
  if (yyn == 299)
    /* "src/main/grammar/zend_language_parser.y":899  */
                        { yyval = null; };
  break;


  case 300: /* return_type: ':' type_expr  */
  if (yyn == 300)
    /* "src/main/grammar/zend_language_parser.y":900  */
                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 301: /* argument_list: '(' ')'  */
  if (yyn == 301)
    /* "src/main/grammar/zend_language_parser.y":904  */
                        { yyval = zend_ast_create_list(0, ZEND_AST_ARG_LIST); };
  break;


  case 302: /* argument_list: '(' non_empty_argument_list possible_comma ')'  */
  if (yyn == 302)
    /* "src/main/grammar/zend_language_parser.y":905  */
                                                               { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 303: /* argument_list: '(' "'...'" ')'  */
  if (yyn == 303)
    /* "src/main/grammar/zend_language_parser.y":906  */
                                   { yyval = zend_ast_create_fcc(); };
  break;


  case 304: /* non_empty_argument_list: argument  */
  if (yyn == 304)
    /* "src/main/grammar/zend_language_parser.y":911  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_ARG_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 305: /* non_empty_argument_list: non_empty_argument_list ',' argument  */
  if (yyn == 305)
    /* "src/main/grammar/zend_language_parser.y":913  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 306: /* clone_argument_list: '(' ')'  */
  if (yyn == 306)
    /* "src/main/grammar/zend_language_parser.y":926  */
                        { yyval = zend_ast_create_list(0, ZEND_AST_ARG_LIST); };
  break;


  case 307: /* clone_argument_list: '(' non_empty_clone_argument_list possible_comma ')'  */
  if (yyn == 307)
    /* "src/main/grammar/zend_language_parser.y":927  */
                                                                     { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 308: /* clone_argument_list: '(' expr ',' ')'  */
  if (yyn == 308)
    /* "src/main/grammar/zend_language_parser.y":928  */
                                 { yyval = zend_ast_create_list(1, ZEND_AST_ARG_LIST, ((ZendAst)(yystack.valueAt (2)))); };
  break;


  case 309: /* clone_argument_list: '(' "'...'" ')'  */
  if (yyn == 309)
    /* "src/main/grammar/zend_language_parser.y":929  */
                                   { yyval = zend_ast_create_fcc(); };
  break;


  case 310: /* non_empty_clone_argument_list: expr ',' argument  */
  if (yyn == 310)
    /* "src/main/grammar/zend_language_parser.y":934  */
                        { yyval = zend_ast_create_list(2, ZEND_AST_ARG_LIST, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 311: /* non_empty_clone_argument_list: argument_no_expr  */
  if (yyn == 311)
    /* "src/main/grammar/zend_language_parser.y":936  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_ARG_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 312: /* non_empty_clone_argument_list: non_empty_clone_argument_list ',' argument  */
  if (yyn == 312)
    /* "src/main/grammar/zend_language_parser.y":938  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 313: /* argument_no_expr: identifier ':' expr  */
  if (yyn == 313)
    /* "src/main/grammar/zend_language_parser.y":943  */
                        { yyval = zend_ast_create(ZEND_AST_NAMED_ARG, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 314: /* argument_no_expr: "'...'" expr  */
  if (yyn == 314)
    /* "src/main/grammar/zend_language_parser.y":944  */
                                { yyval = zend_ast_create(ZEND_AST_UNPACK, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 315: /* argument: expr  */
  if (yyn == 315)
    /* "src/main/grammar/zend_language_parser.y":948  */
                     { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 316: /* argument: argument_no_expr  */
  if (yyn == 316)
    /* "src/main/grammar/zend_language_parser.y":949  */
                                 { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 317: /* global_var_list: global_var_list ',' global_var  */
  if (yyn == 317)
    /* "src/main/grammar/zend_language_parser.y":953  */
                                               { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 318: /* global_var_list: global_var  */
  if (yyn == 318)
    /* "src/main/grammar/zend_language_parser.y":954  */
                           { yyval = zend_ast_create_list(1, ZEND_AST_STMT_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 319: /* global_var: simple_variable  */
  if (yyn == 319)
    /* "src/main/grammar/zend_language_parser.y":959  */
                { yyval = zend_ast_create(ZEND_AST_GLOBAL, zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 320: /* static_var_list: static_var_list ',' static_var  */
  if (yyn == 320)
    /* "src/main/grammar/zend_language_parser.y":964  */
                                               { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 321: /* static_var_list: static_var  */
  if (yyn == 321)
    /* "src/main/grammar/zend_language_parser.y":965  */
                           { yyval = zend_ast_create_list(1, ZEND_AST_STMT_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 322: /* static_var: "variable"  */
  if (yyn == 322)
    /* "src/main/grammar/zend_language_parser.y":969  */
                                                { yyval = zend_ast_create(ZEND_AST_STATIC, ((ZendAst)(yystack.valueAt (0))), null); };
  break;


  case 323: /* static_var: "variable" '=' expr  */
  if (yyn == 323)
    /* "src/main/grammar/zend_language_parser.y":970  */
                                        { yyval = zend_ast_create(ZEND_AST_STATIC, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 324: /* class_statement_list: class_statement_list class_statement  */
  if (yyn == 324)
    /* "src/main/grammar/zend_language_parser.y":975  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


        default: break;
      }
    this.yyval = yyval;
    return YYNEWSTATE;
  }

  private int yyaction_2(int yyn, YYStack yystack)
  {
    Object yyval = this.yyval;
    switch (yyn)
      {
  case 325: /* class_statement_list: %empty  */
  if (yyn == 325)
    /* "src/main/grammar/zend_language_parser.y":977  */
                        { yyval = zend_ast_create_list(0, ZEND_AST_STMT_LIST); };
  break;


  case 326: /* attributed_class_statement: property_modifiers optional_type_without_static property_list ';'  */
  if (yyn == 326)
    /* "src/main/grammar/zend_language_parser.y":983  */
                        { yyval = zend_ast_create(ZEND_AST_PROP_GROUP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (1))), null);
			  ((ZendAst) yyval).attr = ((Integer)(yystack.valueAt (3))); };
  break;


  case 327: /* attributed_class_statement: property_modifiers optional_type_without_static hooked_property  */
  if (yyn == 327)
    /* "src/main/grammar/zend_language_parser.y":986  */
                        { yyval = zend_ast_create(ZEND_AST_PROP_GROUP, ((ZendAst)(yystack.valueAt (1))), zend_ast_create_list(1, ZEND_AST_PROP_DECL, ((ZendAst)(yystack.valueAt (0)))), null);
			  ((ZendAst) yyval).attr = ((Integer)(yystack.valueAt (2))); };
  break;


  case 328: /* attributed_class_statement: class_const_modifiers "'const'" class_const_list ';'  */
  if (yyn == 328)
    /* "src/main/grammar/zend_language_parser.y":989  */
                        { yyval = zend_ast_create(ZEND_AST_CLASS_CONST_GROUP, ((ZendAst)(yystack.valueAt (1))), null, null);
			  ((ZendAst) yyval).attr = ((Integer)(yystack.valueAt (3))); };
  break;


  case 329: /* attributed_class_statement: class_const_modifiers "'const'" type_expr class_const_list ';'  */
  if (yyn == 329)
    /* "src/main/grammar/zend_language_parser.y":992  */
                        { yyval = zend_ast_create(ZEND_AST_CLASS_CONST_GROUP, ((ZendAst)(yystack.valueAt (1))), null, ((ZendAst)(yystack.valueAt (2))));
			  ((ZendAst) yyval).attr = ((Integer)(yystack.valueAt (4))); };
  break;


  case 330: /* attributed_class_statement: method_modifiers function returns_ref identifier backup_doc_comment '(' parameter_list ')' return_type backup_fn_flags method_body backup_fn_flags  */
  if (yyn == 330)
    /* "src/main/grammar/zend_language_parser.y":996  */
                        { yyval = zend_ast_create_decl(ZEND_AST_METHOD, ((Integer)(yystack.valueAt (9))) | ((Integer)(yystack.valueAt (11))) | ((Integer)(yystack.valueAt (0))), ((Integer)(yystack.valueAt (10))), ((byte[])(yystack.valueAt (7))),
				  zend_ast_get_str(((ZendAst)(yystack.valueAt (8)))), ((ZendAst)(yystack.valueAt (5))), null, ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (3))), null); CG.extra_fn_flags = ((Integer)(yystack.valueAt (2))); };
  break;


  case 331: /* attributed_class_statement: enum_case  */
  if (yyn == 331)
    /* "src/main/grammar/zend_language_parser.y":998  */
                          { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 332: /* class_statement: attributed_class_statement  */
  if (yyn == 332)
    /* "src/main/grammar/zend_language_parser.y":1002  */
                                           { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 333: /* class_statement: attributes attributed_class_statement  */
  if (yyn == 333)
    /* "src/main/grammar/zend_language_parser.y":1003  */
                                                      { yyval = zend_ast_with_attributes(((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 334: /* class_statement: "'use'" class_name_list trait_adaptations  */
  if (yyn == 334)
    /* "src/main/grammar/zend_language_parser.y":1005  */
                        { yyval = zend_ast_create(ZEND_AST_USE_TRAIT, ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 335: /* class_name_list: class_name  */
  if (yyn == 335)
    /* "src/main/grammar/zend_language_parser.y":1009  */
                           { yyval = zend_ast_create_list(1, ZEND_AST_NAME_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 336: /* class_name_list: class_name_list ',' class_name  */
  if (yyn == 336)
    /* "src/main/grammar/zend_language_parser.y":1010  */
                                               { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 337: /* trait_adaptations: ';'  */
  if (yyn == 337)
    /* "src/main/grammar/zend_language_parser.y":1014  */
                                                                                { yyval = null; };
  break;


  case 338: /* trait_adaptations: '{' '}'  */
  if (yyn == 338)
    /* "src/main/grammar/zend_language_parser.y":1015  */
                                                                        { yyval = null; };
  break;


  case 339: /* trait_adaptations: '{' trait_adaptation_list '}'  */
  if (yyn == 339)
    /* "src/main/grammar/zend_language_parser.y":1016  */
                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 340: /* trait_adaptation_list: trait_adaptation  */
  if (yyn == 340)
    /* "src/main/grammar/zend_language_parser.y":1021  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_TRAIT_ADAPTATIONS, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 341: /* trait_adaptation_list: trait_adaptation_list trait_adaptation  */
  if (yyn == 341)
    /* "src/main/grammar/zend_language_parser.y":1023  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 342: /* trait_adaptation: trait_precedence ';'  */
  if (yyn == 342)
    /* "src/main/grammar/zend_language_parser.y":1027  */
                                        { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 343: /* trait_adaptation: trait_alias ';'  */
  if (yyn == 343)
    /* "src/main/grammar/zend_language_parser.y":1028  */
                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 344: /* trait_precedence: absolute_trait_method_reference "'insteadof'" class_name_list  */
  if (yyn == 344)
    /* "src/main/grammar/zend_language_parser.y":1033  */
                { yyval = zend_ast_create(ZEND_AST_TRAIT_PRECEDENCE, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 345: /* trait_alias: trait_method_reference "'as'" "identifier"  */
  if (yyn == 345)
    /* "src/main/grammar/zend_language_parser.y":1038  */
                        { yyval = zend_ast_create(ZEND_AST_TRAIT_ALIAS, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 346: /* trait_alias: trait_method_reference "'as'" reserved_non_modifiers  */
  if (yyn == 346)
    /* "src/main/grammar/zend_language_parser.y":1040  */
                        { zval zv = new zval();
			  if (zend_lex_tstring(zv, ((Integer)(yystack.valueAt (0)))) == FAILURE) { return YYABORT; }
			  yyval = zend_ast_create(ZEND_AST_TRAIT_ALIAS, ((ZendAst)(yystack.valueAt (2))), zend_ast_create_zval(zv)); };
  break;


  case 347: /* trait_alias: trait_method_reference "'as'" member_modifier identifier  */
  if (yyn == 347)
    /* "src/main/grammar/zend_language_parser.y":1044  */
                        { int modifiers = zend_modifier_token_to_flag(ZEND_MODIFIER_TARGET_METHOD, ((Integer)(yystack.valueAt (1))));
			  yyval = zend_ast_create_ex(ZEND_AST_TRAIT_ALIAS, modifiers, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (0))));
			  /* identifier nonterminal can cause allocations, so we need to free the node */
			  if ((modifiers == 0)) { zend_ast_destroy(yyval); return YYERROR; } };
  break;


  case 348: /* trait_alias: trait_method_reference "'as'" member_modifier  */
  if (yyn == 348)
    /* "src/main/grammar/zend_language_parser.y":1049  */
                        { int modifiers = zend_modifier_token_to_flag(ZEND_MODIFIER_TARGET_METHOD, ((Integer)(yystack.valueAt (0))));
			  yyval = zend_ast_create_ex(ZEND_AST_TRAIT_ALIAS, modifiers, ((ZendAst)(yystack.valueAt (2))), null);
			  /* identifier nonterminal can cause allocations, so we need to free the node */
			  if ((modifiers == 0)) { zend_ast_destroy(yyval); return YYERROR; } };
  break;


  case 349: /* trait_method_reference: identifier  */
  if (yyn == 349)
    /* "src/main/grammar/zend_language_parser.y":1057  */
                        { yyval = zend_ast_create(ZEND_AST_METHOD_REFERENCE, null, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 350: /* trait_method_reference: absolute_trait_method_reference  */
  if (yyn == 350)
    /* "src/main/grammar/zend_language_parser.y":1058  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 351: /* absolute_trait_method_reference: class_name "'::'" identifier  */
  if (yyn == 351)
    /* "src/main/grammar/zend_language_parser.y":1063  */
                { yyval = zend_ast_create(ZEND_AST_METHOD_REFERENCE, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 352: /* method_body: ';'  */
  if (yyn == 352)
    /* "src/main/grammar/zend_language_parser.y":1067  */
                                                        { yyval = null; };
  break;


  case 353: /* method_body: '{' inner_statement_list '}'  */
  if (yyn == 353)
    /* "src/main/grammar/zend_language_parser.y":1068  */
                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 354: /* property_modifiers: non_empty_member_modifiers  */
  if (yyn == 354)
    /* "src/main/grammar/zend_language_parser.y":1073  */
                        { yyval = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_PROPERTY, ((ZendAst)(yystack.valueAt (0))));
			  if ((((Integer) yyval) == 0)) { return YYERROR; } };
  break;


  case 355: /* property_modifiers: "'var'"  */
  if (yyn == 355)
    /* "src/main/grammar/zend_language_parser.y":1076  */
                        { yyval = ZEND_ACC_PUBLIC; };
  break;


  case 356: /* method_modifiers: %empty  */
  if (yyn == 356)
    /* "src/main/grammar/zend_language_parser.y":1081  */
                        { yyval = ZEND_ACC_PUBLIC; };
  break;


  case 357: /* method_modifiers: non_empty_member_modifiers  */
  if (yyn == 357)
    /* "src/main/grammar/zend_language_parser.y":1083  */
                        { yyval = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_METHOD, ((ZendAst)(yystack.valueAt (0))));
			  if ((((Integer) yyval) == 0)) { return YYERROR; }
			  if (((((Integer) yyval) & ZEND_ACC_PPP_MASK) == 0)) { yyval = ((Integer) yyval) | ZEND_ACC_PUBLIC; } };
  break;


  case 358: /* class_const_modifiers: %empty  */
  if (yyn == 358)
    /* "src/main/grammar/zend_language_parser.y":1090  */
                        { yyval = ZEND_ACC_PUBLIC; };
  break;


  case 359: /* class_const_modifiers: non_empty_member_modifiers  */
  if (yyn == 359)
    /* "src/main/grammar/zend_language_parser.y":1092  */
                        { yyval = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_CONSTANT, ((ZendAst)(yystack.valueAt (0))));
			  if ((((Integer) yyval) == 0)) { return YYERROR; }
			  if (((((Integer) yyval) & ZEND_ACC_PPP_MASK) == 0)) { yyval = ((Integer) yyval) | ZEND_ACC_PUBLIC; } };
  break;


  case 360: /* non_empty_member_modifiers: member_modifier  */
  if (yyn == 360)
    /* "src/main/grammar/zend_language_parser.y":1099  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_MODIFIER_LIST, zend_ast_create_zval_from_long(((Integer)(yystack.valueAt (0))))); };
  break;


  case 361: /* non_empty_member_modifiers: non_empty_member_modifiers member_modifier  */
  if (yyn == 361)
    /* "src/main/grammar/zend_language_parser.y":1101  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), zend_ast_create_zval_from_long(((Integer)(yystack.valueAt (0))))); };
  break;


  case 362: /* member_modifier: "'public'"  */
  if (yyn == 362)
    /* "src/main/grammar/zend_language_parser.y":1105  */
                                                        { yyval = T_PUBLIC; };
  break;


  case 363: /* member_modifier: "'protected'"  */
  if (yyn == 363)
    /* "src/main/grammar/zend_language_parser.y":1106  */
                                                        { yyval = T_PROTECTED; };
  break;


  case 364: /* member_modifier: "'private'"  */
  if (yyn == 364)
    /* "src/main/grammar/zend_language_parser.y":1107  */
                                                        { yyval = T_PRIVATE; };
  break;


  case 365: /* member_modifier: "'public(set)'"  */
  if (yyn == 365)
    /* "src/main/grammar/zend_language_parser.y":1108  */
                                                { yyval = T_PUBLIC_SET; };
  break;


  case 366: /* member_modifier: "'protected(set)'"  */
  if (yyn == 366)
    /* "src/main/grammar/zend_language_parser.y":1109  */
                                                { yyval = T_PROTECTED_SET; };
  break;


  case 367: /* member_modifier: "'private(set)'"  */
  if (yyn == 367)
    /* "src/main/grammar/zend_language_parser.y":1110  */
                                                { yyval = T_PRIVATE_SET; };
  break;


  case 368: /* member_modifier: "'static'"  */
  if (yyn == 368)
    /* "src/main/grammar/zend_language_parser.y":1111  */
                                                        { yyval = T_STATIC; };
  break;


  case 369: /* member_modifier: "'abstract'"  */
  if (yyn == 369)
    /* "src/main/grammar/zend_language_parser.y":1112  */
                                                        { yyval = T_ABSTRACT; };
  break;


  case 370: /* member_modifier: "'final'"  */
  if (yyn == 370)
    /* "src/main/grammar/zend_language_parser.y":1113  */
                                                        { yyval = T_FINAL; };
  break;


  case 371: /* member_modifier: "'readonly'"  */
  if (yyn == 371)
    /* "src/main/grammar/zend_language_parser.y":1114  */
                                                        { yyval = T_READONLY; };
  break;


  case 372: /* property_list: property_list ',' property  */
  if (yyn == 372)
    /* "src/main/grammar/zend_language_parser.y":1118  */
                                           { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 373: /* property_list: property  */
  if (yyn == 373)
    /* "src/main/grammar/zend_language_parser.y":1119  */
                         { yyval = zend_ast_create_list(1, ZEND_AST_PROP_DECL, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 374: /* property: "variable" backup_doc_comment  */
  if (yyn == 374)
    /* "src/main/grammar/zend_language_parser.y":1124  */
                        { yyval = zend_ast_create(ZEND_AST_PROP_ELEM, ((ZendAst)(yystack.valueAt (1))), null, ((((byte[])(yystack.valueAt (0))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (0)))) : null), null); };
  break;


  case 375: /* property: "variable" '=' expr backup_doc_comment  */
  if (yyn == 375)
    /* "src/main/grammar/zend_language_parser.y":1126  */
                        { yyval = zend_ast_create(ZEND_AST_PROP_ELEM, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1))), ((((byte[])(yystack.valueAt (0))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (0)))) : null), null); };
  break;


  case 376: /* hooked_property: "variable" backup_doc_comment '{' property_hook_list '}'  */
  if (yyn == 376)
    /* "src/main/grammar/zend_language_parser.y":1131  */
                        { yyval = zend_ast_create(ZEND_AST_PROP_ELEM, ((ZendAst)(yystack.valueAt (4))), null, ((((byte[])(yystack.valueAt (3))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (3)))) : null), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 377: /* hooked_property: "variable" '=' expr backup_doc_comment '{' property_hook_list '}'  */
  if (yyn == 377)
    /* "src/main/grammar/zend_language_parser.y":1133  */
                        { yyval = zend_ast_create(ZEND_AST_PROP_ELEM, ((ZendAst)(yystack.valueAt (6))), ((ZendAst)(yystack.valueAt (4))), ((((byte[])(yystack.valueAt (3))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (3)))) : null), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 378: /* property_hook_list: %empty  */
  if (yyn == 378)
    /* "src/main/grammar/zend_language_parser.y":1137  */
                       { yyval = zend_ast_create_list(0, ZEND_AST_STMT_LIST); };
  break;


  case 379: /* property_hook_list: property_hook_list property_hook  */
  if (yyn == 379)
    /* "src/main/grammar/zend_language_parser.y":1138  */
                                                 { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 380: /* property_hook_list: property_hook_list attributes property_hook  */
  if (yyn == 380)
    /* "src/main/grammar/zend_language_parser.y":1139  */
                                                            {
			yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), zend_ast_with_attributes(((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (1)))));
		};
  break;


  case 381: /* optional_property_hook_list: %empty  */
  if (yyn == 381)
    /* "src/main/grammar/zend_language_parser.y":1145  */
                        { yyval = null; };
  break;


  case 382: /* optional_property_hook_list: '{' property_hook_list '}'  */
  if (yyn == 382)
    /* "src/main/grammar/zend_language_parser.y":1146  */
                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 383: /* property_hook_modifiers: %empty  */
  if (yyn == 383)
    /* "src/main/grammar/zend_language_parser.y":1150  */
                       { yyval = 0; };
  break;


  case 384: /* property_hook_modifiers: non_empty_member_modifiers  */
  if (yyn == 384)
    /* "src/main/grammar/zend_language_parser.y":1151  */
                                           {
			yyval = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_PROPERTY_HOOK, ((ZendAst)(yystack.valueAt (0))));
			if ((((Integer) yyval) == 0)) { return YYERROR; }
		};
  break;


  case 385: /* @9: %empty  */
  if (yyn == 385)
    /* "src/main/grammar/zend_language_parser.y":1159  */
                                   { yyval = CG.zend_lineno; };
  break;


  case 386: /* property_hook: property_hook_modifiers returns_ref "identifier" backup_doc_comment @9 optional_parameter_list backup_fn_flags property_hook_body backup_fn_flags  */
  if (yyn == 386)
    /* "src/main/grammar/zend_language_parser.y":1160  */
                                                                                           {
			yyval = zend_ast_create_decl(
				ZEND_AST_PROPERTY_HOOK, ((Integer)(yystack.valueAt (8))) | ((Integer)(yystack.valueAt (7))) | ((Integer)(yystack.valueAt (0))), ((Integer)(yystack.valueAt (4))), ((byte[])(yystack.valueAt (5))), zend_ast_get_str(((ZendAst)(yystack.valueAt (6)))),
				((ZendAst)(yystack.valueAt (3))), null, ((ZendAst)(yystack.valueAt (1))), null, null);
			CG.extra_fn_flags = ((Integer)(yystack.valueAt (2)));
		};
  break;


  case 387: /* property_hook_body: ';'  */
  if (yyn == 387)
    /* "src/main/grammar/zend_language_parser.y":1169  */
                    { yyval = null; };
  break;


  case 388: /* property_hook_body: '{' inner_statement_list '}'  */
  if (yyn == 388)
    /* "src/main/grammar/zend_language_parser.y":1170  */
                                             { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 389: /* property_hook_body: "'=>'" expr ';'  */
  if (yyn == 389)
    /* "src/main/grammar/zend_language_parser.y":1172  */
                        { yyval = zend_ast_create(ZEND_AST_PROPERTY_HOOK_SHORT_BODY, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 390: /* optional_parameter_list: %empty  */
  if (yyn == 390)
    /* "src/main/grammar/zend_language_parser.y":1176  */
                       { yyval = null; };
  break;


  case 391: /* optional_parameter_list: '(' parameter_list ')'  */
  if (yyn == 391)
    /* "src/main/grammar/zend_language_parser.y":1177  */
                                       { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 392: /* class_const_list: class_const_list ',' class_const_decl  */
  if (yyn == 392)
    /* "src/main/grammar/zend_language_parser.y":1181  */
                                                      { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 393: /* class_const_list: class_const_decl  */
  if (yyn == 393)
    /* "src/main/grammar/zend_language_parser.y":1182  */
                                 { yyval = zend_ast_create_list(1, ZEND_AST_CLASS_CONST_DECL, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 394: /* class_const_decl: "identifier" '=' expr backup_doc_comment  */
  if (yyn == 394)
    /* "src/main/grammar/zend_language_parser.y":1186  */
                                                     { yyval = zend_ast_create(ZEND_AST_CONST_ELEM, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1))), ((((byte[])(yystack.valueAt (0))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (0)))) : null)); };
  break;


  case 395: /* class_const_decl: semi_reserved '=' expr backup_doc_comment  */
  if (yyn == 395)
    /* "src/main/grammar/zend_language_parser.y":1187  */
                                                          {
			zval zv = new zval();
			if (zend_lex_tstring(zv, ((Integer)(yystack.valueAt (3)))) == FAILURE) { return YYABORT; }
			yyval = zend_ast_create(ZEND_AST_CONST_ELEM, zend_ast_create_zval(zv), ((ZendAst)(yystack.valueAt (1))), ((((byte[])(yystack.valueAt (0))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (0)))) : null));
		};
  break;


  case 396: /* const_decl: "identifier" '=' expr backup_doc_comment  */
  if (yyn == 396)
    /* "src/main/grammar/zend_language_parser.y":1195  */
                                             { yyval = zend_ast_create(ZEND_AST_CONST_ELEM, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1))), ((((byte[])(yystack.valueAt (0))) != null) ? zend_ast_create_zval_from_str(((byte[])(yystack.valueAt (0)))) : null)); };
  break;


  case 397: /* echo_expr_list: echo_expr_list ',' echo_expr  */
  if (yyn == 397)
    /* "src/main/grammar/zend_language_parser.y":1199  */
                                             { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 398: /* echo_expr_list: echo_expr  */
  if (yyn == 398)
    /* "src/main/grammar/zend_language_parser.y":1200  */
                          { yyval = zend_ast_create_list(1, ZEND_AST_STMT_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 399: /* echo_expr: expr  */
  if (yyn == 399)
    /* "src/main/grammar/zend_language_parser.y":1203  */
             { yyval = zend_ast_create(ZEND_AST_ECHO, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 400: /* for_cond_exprs: %empty  */
  if (yyn == 400)
    /* "src/main/grammar/zend_language_parser.y":1207  */
                                        { yyval = null; };
  break;


  case 401: /* for_cond_exprs: non_empty_for_exprs ',' expr  */
  if (yyn == 401)
    /* "src/main/grammar/zend_language_parser.y":1208  */
                                             { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 402: /* for_cond_exprs: expr  */
  if (yyn == 402)
    /* "src/main/grammar/zend_language_parser.y":1209  */
                     { yyval = zend_ast_create_list(1, ZEND_AST_EXPR_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 403: /* for_exprs: %empty  */
  if (yyn == 403)
    /* "src/main/grammar/zend_language_parser.y":1213  */
                                        { yyval = null; };
  break;


  case 404: /* for_exprs: non_empty_for_exprs  */
  if (yyn == 404)
    /* "src/main/grammar/zend_language_parser.y":1214  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 405: /* non_empty_for_exprs: non_empty_for_exprs ',' expr  */
  if (yyn == 405)
    /* "src/main/grammar/zend_language_parser.y":1218  */
                                             { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 406: /* non_empty_for_exprs: non_empty_for_exprs ',' "'(void)'" expr  */
  if (yyn == 406)
    /* "src/main/grammar/zend_language_parser.y":1219  */
                                                         { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (3))), zend_ast_create(ZEND_AST_CAST_VOID, ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 407: /* non_empty_for_exprs: "'(void)'" expr  */
  if (yyn == 407)
    /* "src/main/grammar/zend_language_parser.y":1220  */
                                 { yyval = zend_ast_create_list(1, ZEND_AST_EXPR_LIST, zend_ast_create(ZEND_AST_CAST_VOID, ((ZendAst)(yystack.valueAt (0))))); };
  break;


  case 408: /* non_empty_for_exprs: expr  */
  if (yyn == 408)
    /* "src/main/grammar/zend_language_parser.y":1221  */
                     { yyval = zend_ast_create_list(1, ZEND_AST_EXPR_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 409: /* @10: %empty  */
  if (yyn == 409)
    /* "src/main/grammar/zend_language_parser.y":1225  */
                                                           { yyval = CG.zend_lineno; };
  break;


  case 410: /* anonymous_class: anonymous_class_modifiers_optional "'class'" @10 ctor_arguments extends_from implements_list backup_doc_comment '{' class_statement_list '}'  */
  if (yyn == 410)
    /* "src/main/grammar/zend_language_parser.y":1226  */
                                                                                             {
			ZendAst decl = zend_ast_create_decl(
				ZEND_AST_CLASS, ZEND_ACC_ANON_CLASS | ((Integer)(yystack.valueAt (9))), ((Integer)(yystack.valueAt (7))), ((byte[])(yystack.valueAt (3))), null,
				((ZendAst)(yystack.valueAt (5))), ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1))), null, null);
			yyval = zend_ast_create(ZEND_AST_NEW, decl, ((ZendAst)(yystack.valueAt (6))));
		};
  break;


  case 411: /* new_dereferenceable: "'new'" class_name_reference argument_list  */
  if (yyn == 411)
    /* "src/main/grammar/zend_language_parser.y":1236  */
                        { yyval = zend_ast_create(ZEND_AST_NEW, ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 412: /* new_dereferenceable: "'new'" anonymous_class  */
  if (yyn == 412)
    /* "src/main/grammar/zend_language_parser.y":1238  */
                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 413: /* new_dereferenceable: "'new'" attributes anonymous_class  */
  if (yyn == 413)
    /* "src/main/grammar/zend_language_parser.y":1240  */
                        { zend_ast_with_attributes(((ZendAst)(yystack.valueAt (0))).child[0], ((ZendAst)(yystack.valueAt (1)))); yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 414: /* new_non_dereferenceable: "'new'" class_name_reference  */
  if (yyn == 414)
    /* "src/main/grammar/zend_language_parser.y":1245  */
                        { yyval = zend_ast_create(ZEND_AST_NEW, ((ZendAst)(yystack.valueAt (0))), zend_ast_create_list(0, ZEND_AST_ARG_LIST)); };
  break;


  case 415: /* expr: variable  */
  if (yyn == 415)
    /* "src/main/grammar/zend_language_parser.y":1250  */
                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 416: /* expr: "'list'" '(' array_pair_list ')' '=' expr  */
  if (yyn == 416)
    /* "src/main/grammar/zend_language_parser.y":1252  */
                        { ((ZendAst)(yystack.valueAt (3))).attr = ZEND_ARRAY_SYNTAX_LIST; yyval = zend_ast_create(ZEND_AST_ASSIGN, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 417: /* expr: '[' array_pair_list ']' '=' expr  */
  if (yyn == 417)
    /* "src/main/grammar/zend_language_parser.y":1254  */
                        { ((ZendAst)(yystack.valueAt (3))).attr = ZEND_ARRAY_SYNTAX_SHORT; yyval = zend_ast_create(ZEND_AST_ASSIGN, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 418: /* expr: variable '=' expr  */
  if (yyn == 418)
    /* "src/main/grammar/zend_language_parser.y":1256  */
                        { yyval = zend_ast_create(ZEND_AST_ASSIGN, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 419: /* expr: variable '=' ampersand variable  */
  if (yyn == 419)
    /* "src/main/grammar/zend_language_parser.y":1258  */
                        { yyval = zend_ast_create(ZEND_AST_ASSIGN_REF, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 420: /* expr: "'clone'" clone_argument_list  */
  if (yyn == 420)
    /* "src/main/grammar/zend_language_parser.y":1259  */
                                            {
			ZendAst name = zend_ast_create_zval_from_str(ZSTR_KNOWN(ZEND_STR_CLONE));
			name.attr = ZEND_NAME_FQ;
			yyval = zend_ast_create(ZEND_AST_CALL, name, ((ZendAst)(yystack.valueAt (0))));
		};
  break;


  case 421: /* expr: "'clone'" expr  */
  if (yyn == 421)
    /* "src/main/grammar/zend_language_parser.y":1264  */
                             {
			ZendAst name = zend_ast_create_zval_from_str(ZSTR_KNOWN(ZEND_STR_CLONE));
			name.attr = ZEND_NAME_FQ;
			yyval = zend_ast_create(ZEND_AST_CALL, name, zend_ast_create_list(1, ZEND_AST_ARG_LIST, ((ZendAst)(yystack.valueAt (0)))));
		};
  break;


  case 422: /* expr: variable "'+='" expr  */
  if (yyn == 422)
    /* "src/main/grammar/zend_language_parser.y":1270  */
                        { yyval = zend_ast_create_assign_op(ZEND_ADD, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 423: /* expr: variable "'-='" expr  */
  if (yyn == 423)
    /* "src/main/grammar/zend_language_parser.y":1272  */
                        { yyval = zend_ast_create_assign_op(ZEND_SUB, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 424: /* expr: variable "'*='" expr  */
  if (yyn == 424)
    /* "src/main/grammar/zend_language_parser.y":1274  */
                        { yyval = zend_ast_create_assign_op(ZEND_MUL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 425: /* expr: variable "'**='" expr  */
  if (yyn == 425)
    /* "src/main/grammar/zend_language_parser.y":1276  */
                        { yyval = zend_ast_create_assign_op(ZEND_POW, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 426: /* expr: variable "'/='" expr  */
  if (yyn == 426)
    /* "src/main/grammar/zend_language_parser.y":1278  */
                        { yyval = zend_ast_create_assign_op(ZEND_DIV, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 427: /* expr: variable "'.='" expr  */
  if (yyn == 427)
    /* "src/main/grammar/zend_language_parser.y":1280  */
                        { yyval = zend_ast_create_assign_op(ZEND_CONCAT, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 428: /* expr: variable "'%='" expr  */
  if (yyn == 428)
    /* "src/main/grammar/zend_language_parser.y":1282  */
                        { yyval = zend_ast_create_assign_op(ZEND_MOD, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 429: /* expr: variable "'&='" expr  */
  if (yyn == 429)
    /* "src/main/grammar/zend_language_parser.y":1284  */
                        { yyval = zend_ast_create_assign_op(ZEND_BW_AND, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 430: /* expr: variable "'|='" expr  */
  if (yyn == 430)
    /* "src/main/grammar/zend_language_parser.y":1286  */
                        { yyval = zend_ast_create_assign_op(ZEND_BW_OR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 431: /* expr: variable "'^='" expr  */
  if (yyn == 431)
    /* "src/main/grammar/zend_language_parser.y":1288  */
                        { yyval = zend_ast_create_assign_op(ZEND_BW_XOR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 432: /* expr: variable "'<<='" expr  */
  if (yyn == 432)
    /* "src/main/grammar/zend_language_parser.y":1290  */
                        { yyval = zend_ast_create_assign_op(ZEND_SL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 433: /* expr: variable "'>>='" expr  */
  if (yyn == 433)
    /* "src/main/grammar/zend_language_parser.y":1292  */
                        { yyval = zend_ast_create_assign_op(ZEND_SR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 434: /* expr: variable "'??='" expr  */
  if (yyn == 434)
    /* "src/main/grammar/zend_language_parser.y":1294  */
                        { yyval = zend_ast_create(ZEND_AST_ASSIGN_COALESCE, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 435: /* expr: variable "'++'"  */
  if (yyn == 435)
    /* "src/main/grammar/zend_language_parser.y":1295  */
                               { yyval = zend_ast_create(ZEND_AST_POST_INC, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 436: /* expr: "'++'" variable  */
  if (yyn == 436)
    /* "src/main/grammar/zend_language_parser.y":1296  */
                               { yyval = zend_ast_create(ZEND_AST_PRE_INC, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 437: /* expr: variable "'--'"  */
  if (yyn == 437)
    /* "src/main/grammar/zend_language_parser.y":1297  */
                               { yyval = zend_ast_create(ZEND_AST_POST_DEC, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 438: /* expr: "'--'" variable  */
  if (yyn == 438)
    /* "src/main/grammar/zend_language_parser.y":1298  */
                               { yyval = zend_ast_create(ZEND_AST_PRE_DEC, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 439: /* expr: expr "'||'" expr  */
  if (yyn == 439)
    /* "src/main/grammar/zend_language_parser.y":1300  */
                        { yyval = zend_ast_create(ZEND_AST_OR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 440: /* expr: expr "'&&'" expr  */
  if (yyn == 440)
    /* "src/main/grammar/zend_language_parser.y":1302  */
                        { yyval = zend_ast_create(ZEND_AST_AND, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 441: /* expr: expr "'or'" expr  */
  if (yyn == 441)
    /* "src/main/grammar/zend_language_parser.y":1304  */
                        { yyval = zend_ast_create(ZEND_AST_OR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 442: /* expr: expr "'and'" expr  */
  if (yyn == 442)
    /* "src/main/grammar/zend_language_parser.y":1306  */
                        { yyval = zend_ast_create(ZEND_AST_AND, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 443: /* expr: expr "'xor'" expr  */
  if (yyn == 443)
    /* "src/main/grammar/zend_language_parser.y":1308  */
                        { yyval = zend_ast_create_binary_op(ZEND_BOOL_XOR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 444: /* expr: expr '|' expr  */
  if (yyn == 444)
    /* "src/main/grammar/zend_language_parser.y":1309  */
                                { yyval = zend_ast_create_binary_op(ZEND_BW_OR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


        default: break;
      }
    this.yyval = yyval;
    return YYNEWSTATE;
  }

  private int yyaction_3(int yyn, YYStack yystack)
  {
    Object yyval = this.yyval;
    switch (yyn)
      {
  case 445: /* expr: expr "amp" expr  */
  if (yyn == 445)
    /* "src/main/grammar/zend_language_parser.y":1310  */
                                                                        { yyval = zend_ast_create_binary_op(ZEND_BW_AND, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 446: /* expr: expr "'&'" expr  */
  if (yyn == 446)
    /* "src/main/grammar/zend_language_parser.y":1311  */
                                                                { yyval = zend_ast_create_binary_op(ZEND_BW_AND, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 447: /* expr: expr '^' expr  */
  if (yyn == 447)
    /* "src/main/grammar/zend_language_parser.y":1312  */
                                { yyval = zend_ast_create_binary_op(ZEND_BW_XOR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 448: /* expr: expr '.' expr  */
  if (yyn == 448)
    /* "src/main/grammar/zend_language_parser.y":1313  */
                                { yyval = zend_ast_create_concat_op(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 449: /* expr: expr '+' expr  */
  if (yyn == 449)
    /* "src/main/grammar/zend_language_parser.y":1314  */
                                { yyval = zend_ast_create_binary_op(ZEND_ADD, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 450: /* expr: expr '-' expr  */
  if (yyn == 450)
    /* "src/main/grammar/zend_language_parser.y":1315  */
                                { yyval = zend_ast_create_binary_op(ZEND_SUB, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 451: /* expr: expr '*' expr  */
  if (yyn == 451)
    /* "src/main/grammar/zend_language_parser.y":1316  */
                                { yyval = zend_ast_create_binary_op(ZEND_MUL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 452: /* expr: expr "'**'" expr  */
  if (yyn == 452)
    /* "src/main/grammar/zend_language_parser.y":1317  */
                                { yyval = zend_ast_create_binary_op(ZEND_POW, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 453: /* expr: expr '/' expr  */
  if (yyn == 453)
    /* "src/main/grammar/zend_language_parser.y":1318  */
                                { yyval = zend_ast_create_binary_op(ZEND_DIV, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 454: /* expr: expr '%' expr  */
  if (yyn == 454)
    /* "src/main/grammar/zend_language_parser.y":1319  */
                                { yyval = zend_ast_create_binary_op(ZEND_MOD, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 455: /* expr: expr "'<<'" expr  */
  if (yyn == 455)
    /* "src/main/grammar/zend_language_parser.y":1320  */
                                { yyval = zend_ast_create_binary_op(ZEND_SL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 456: /* expr: expr "'>>'" expr  */
  if (yyn == 456)
    /* "src/main/grammar/zend_language_parser.y":1321  */
                                { yyval = zend_ast_create_binary_op(ZEND_SR, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 457: /* expr: '+' expr  */
  if (yyn == 457)
    /* "src/main/grammar/zend_language_parser.y":1322  */
                                   { yyval = zend_ast_create(ZEND_AST_UNARY_PLUS, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 458: /* expr: '-' expr  */
  if (yyn == 458)
    /* "src/main/grammar/zend_language_parser.y":1323  */
                                   { yyval = zend_ast_create(ZEND_AST_UNARY_MINUS, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 459: /* expr: '!' expr  */
  if (yyn == 459)
    /* "src/main/grammar/zend_language_parser.y":1324  */
                         { yyval = zend_ast_create_ex(ZEND_AST_UNARY_OP, ZEND_BOOL_NOT, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 460: /* expr: '~' expr  */
  if (yyn == 460)
    /* "src/main/grammar/zend_language_parser.y":1325  */
                         { yyval = zend_ast_create_ex(ZEND_AST_UNARY_OP, ZEND_BW_NOT, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 461: /* expr: expr "'==='" expr  */
  if (yyn == 461)
    /* "src/main/grammar/zend_language_parser.y":1327  */
                        { yyval = zend_ast_create_binary_op(ZEND_IS_IDENTICAL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 462: /* expr: expr "'!=='" expr  */
  if (yyn == 462)
    /* "src/main/grammar/zend_language_parser.y":1329  */
                        { yyval = zend_ast_create_binary_op(ZEND_IS_NOT_IDENTICAL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 463: /* expr: expr "'=='" expr  */
  if (yyn == 463)
    /* "src/main/grammar/zend_language_parser.y":1331  */
                        { yyval = zend_ast_create_binary_op(ZEND_IS_EQUAL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 464: /* expr: expr "'!='" expr  */
  if (yyn == 464)
    /* "src/main/grammar/zend_language_parser.y":1333  */
                        { yyval = zend_ast_create_binary_op(ZEND_IS_NOT_EQUAL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 465: /* expr: expr "'|>'" expr  */
  if (yyn == 465)
    /* "src/main/grammar/zend_language_parser.y":1335  */
                        { yyval = zend_ast_create(ZEND_AST_PIPE, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 466: /* expr: expr '<' expr  */
  if (yyn == 466)
    /* "src/main/grammar/zend_language_parser.y":1337  */
                        { yyval = zend_ast_create_binary_op(ZEND_IS_SMALLER, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 467: /* expr: expr "'<='" expr  */
  if (yyn == 467)
    /* "src/main/grammar/zend_language_parser.y":1339  */
                        { yyval = zend_ast_create_binary_op(ZEND_IS_SMALLER_OR_EQUAL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 468: /* expr: expr '>' expr  */
  if (yyn == 468)
    /* "src/main/grammar/zend_language_parser.y":1341  */
                        { yyval = zend_ast_create(ZEND_AST_GREATER, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 469: /* expr: expr "'>='" expr  */
  if (yyn == 469)
    /* "src/main/grammar/zend_language_parser.y":1343  */
                        { yyval = zend_ast_create(ZEND_AST_GREATER_EQUAL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 470: /* expr: expr "'<=>'" expr  */
  if (yyn == 470)
    /* "src/main/grammar/zend_language_parser.y":1345  */
                        { yyval = zend_ast_create_binary_op(ZEND_SPACESHIP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 471: /* expr: expr "'instanceof'" class_name_reference  */
  if (yyn == 471)
    /* "src/main/grammar/zend_language_parser.y":1347  */
                        { yyval = zend_ast_create(ZEND_AST_INSTANCEOF, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 472: /* expr: '(' expr ')'  */
  if (yyn == 472)
    /* "src/main/grammar/zend_language_parser.y":1348  */
                             {
			yyval = ((ZendAst)(yystack.valueAt (1)));
			if (((ZendAst) yyval).kind == ZEND_AST_CONDITIONAL) ((ZendAst) yyval).attr = ZEND_PARENTHESIZED_CONDITIONAL;
			if (((ZendAst) yyval).kind == ZEND_AST_ARROW_FUNC) ((ZendAst) yyval).attr = ZEND_PARENTHESIZED_ARROW_FUNC;
		};
  break;


  case 473: /* expr: new_dereferenceable  */
  if (yyn == 473)
    /* "src/main/grammar/zend_language_parser.y":1353  */
                                    { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 474: /* expr: new_non_dereferenceable  */
  if (yyn == 474)
    /* "src/main/grammar/zend_language_parser.y":1354  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 475: /* expr: expr '?' expr ':' expr  */
  if (yyn == 475)
    /* "src/main/grammar/zend_language_parser.y":1356  */
                        { yyval = zend_ast_create(ZEND_AST_CONDITIONAL, ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 476: /* expr: expr '?' ':' expr  */
  if (yyn == 476)
    /* "src/main/grammar/zend_language_parser.y":1358  */
                        { yyval = zend_ast_create(ZEND_AST_CONDITIONAL, ((ZendAst)(yystack.valueAt (3))), null, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 477: /* expr: expr "'??'" expr  */
  if (yyn == 477)
    /* "src/main/grammar/zend_language_parser.y":1360  */
                        { yyval = zend_ast_create(ZEND_AST_COALESCE, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 478: /* expr: internal_functions_in_yacc  */
  if (yyn == 478)
    /* "src/main/grammar/zend_language_parser.y":1361  */
                                           { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 479: /* expr: "'(int)'" expr  */
  if (yyn == 479)
    /* "src/main/grammar/zend_language_parser.y":1362  */
                                        { yyval = zend_ast_create_cast(IS_LONG, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 480: /* expr: "'(float)'" expr  */
  if (yyn == 480)
    /* "src/main/grammar/zend_language_parser.y":1363  */
                                        { yyval = zend_ast_create_cast(IS_DOUBLE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 481: /* expr: "'(string)'" expr  */
  if (yyn == 481)
    /* "src/main/grammar/zend_language_parser.y":1364  */
                                        { yyval = zend_ast_create_cast(IS_STRING, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 482: /* expr: "'(array)'" expr  */
  if (yyn == 482)
    /* "src/main/grammar/zend_language_parser.y":1365  */
                                        { yyval = zend_ast_create_cast(IS_ARRAY, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 483: /* expr: "'(object)'" expr  */
  if (yyn == 483)
    /* "src/main/grammar/zend_language_parser.y":1366  */
                                        { yyval = zend_ast_create_cast(IS_OBJECT, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 484: /* expr: "'(bool)'" expr  */
  if (yyn == 484)
    /* "src/main/grammar/zend_language_parser.y":1367  */
                                        { yyval = zend_ast_create_cast(_IS_BOOL, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 485: /* expr: "'(unset)'" expr  */
  if (yyn == 485)
    /* "src/main/grammar/zend_language_parser.y":1368  */
                                        { yyval = zend_ast_create_cast(IS_NULL, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 486: /* expr: "'exit'" ctor_arguments  */
  if (yyn == 486)
    /* "src/main/grammar/zend_language_parser.y":1369  */
                                      {
			ZendAst name = zend_ast_create_zval_from_str(ZSTR_KNOWN(ZEND_STR_EXIT));
			name.attr = ZEND_NAME_FQ;
			yyval = zend_ast_create(ZEND_AST_CALL, name, ((ZendAst)(yystack.valueAt (0))));
		};
  break;


  case 487: /* expr: '@' expr  */
  if (yyn == 487)
    /* "src/main/grammar/zend_language_parser.y":1374  */
                                                { yyval = zend_ast_create(ZEND_AST_SILENCE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 488: /* expr: scalar  */
  if (yyn == 488)
    /* "src/main/grammar/zend_language_parser.y":1375  */
                       { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 489: /* expr: '`' backticks_expr '`'  */
  if (yyn == 489)
    /* "src/main/grammar/zend_language_parser.y":1376  */
                                       { yyval = zend_ast_create(ZEND_AST_SHELL_EXEC, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 490: /* expr: "'print'" expr  */
  if (yyn == 490)
    /* "src/main/grammar/zend_language_parser.y":1377  */
                             { yyval = zend_ast_create(ZEND_AST_PRINT, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 491: /* expr: "'yield'"  */
  if (yyn == 491)
    /* "src/main/grammar/zend_language_parser.y":1378  */
                        { yyval = zend_ast_create(ZEND_AST_YIELD, null, null); CG.extra_fn_flags |= ZEND_ACC_GENERATOR; };
  break;


  case 492: /* expr: "'yield'" expr  */
  if (yyn == 492)
    /* "src/main/grammar/zend_language_parser.y":1379  */
                             { yyval = zend_ast_create(ZEND_AST_YIELD, ((ZendAst)(yystack.valueAt (0))), null); CG.extra_fn_flags |= ZEND_ACC_GENERATOR; };
  break;


  case 493: /* expr: "'yield'" expr "'=>'" expr  */
  if (yyn == 493)
    /* "src/main/grammar/zend_language_parser.y":1380  */
                                                 { yyval = zend_ast_create(ZEND_AST_YIELD, ((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (2)))); CG.extra_fn_flags |= ZEND_ACC_GENERATOR; };
  break;


  case 494: /* expr: "'yield from'" expr  */
  if (yyn == 494)
    /* "src/main/grammar/zend_language_parser.y":1381  */
                                  { yyval = zend_ast_create(ZEND_AST_YIELD_FROM, ((ZendAst)(yystack.valueAt (0)))); CG.extra_fn_flags |= ZEND_ACC_GENERATOR; };
  break;


  case 495: /* expr: "'throw'" expr  */
  if (yyn == 495)
    /* "src/main/grammar/zend_language_parser.y":1382  */
                             { yyval = zend_ast_create(ZEND_AST_THROW, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 496: /* expr: inline_function  */
  if (yyn == 496)
    /* "src/main/grammar/zend_language_parser.y":1383  */
                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 497: /* expr: attributes inline_function  */
  if (yyn == 497)
    /* "src/main/grammar/zend_language_parser.y":1384  */
                                           { yyval = zend_ast_with_attributes(((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 498: /* expr: "'static'" inline_function  */
  if (yyn == 498)
    /* "src/main/grammar/zend_language_parser.y":1385  */
                                         { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAstDecl) yyval).flags |= ZEND_ACC_STATIC; };
  break;


  case 499: /* expr: attributes "'static'" inline_function  */
  if (yyn == 499)
    /* "src/main/grammar/zend_language_parser.y":1387  */
                        { yyval = zend_ast_with_attributes(((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (2)))); ((ZendAstDecl) yyval).flags |= ZEND_ACC_STATIC; };
  break;


  case 500: /* expr: match  */
  if (yyn == 500)
    /* "src/main/grammar/zend_language_parser.y":1388  */
                      { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 501: /* inline_function: function returns_ref backup_doc_comment '(' parameter_list ')' lexical_vars return_type backup_fn_flags '{' inner_statement_list '}' backup_fn_flags  */
  if (yyn == 501)
    /* "src/main/grammar/zend_language_parser.y":1395  */
                        { yyval = zend_ast_create_decl(ZEND_AST_CLOSURE, ((Integer)(yystack.valueAt (11))) | ((Integer)(yystack.valueAt (0))), ((Integer)(yystack.valueAt (12))), ((byte[])(yystack.valueAt (10))),
				  null,
				  ((ZendAst)(yystack.valueAt (8))), ((ZendAst)(yystack.valueAt (6))), ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (5))), null); CG.extra_fn_flags = ((Integer)(yystack.valueAt (4))); };
  break;


  case 502: /* inline_function: fn returns_ref backup_doc_comment '(' parameter_list ')' return_type "'=>'" backup_fn_flags backup_lex_pos expr backup_fn_flags  */
  if (yyn == 502)
    /* "src/main/grammar/zend_language_parser.y":1400  */
                        { yyval = zend_ast_create_decl(ZEND_AST_ARROW_FUNC, ((Integer)(yystack.valueAt (10))) | ((Integer)(yystack.valueAt (0))), ((Integer)(yystack.valueAt (11))), ((byte[])(yystack.valueAt (9))),
				  null, ((ZendAst)(yystack.valueAt (7))), null, ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (5))), null);
				  CG.extra_fn_flags = ((Integer)(yystack.valueAt (3))); };
  break;


  case 503: /* fn: "'fn'"  */
  if (yyn == 503)
    /* "src/main/grammar/zend_language_parser.y":1406  */
             { yyval = CG.zend_lineno; };
  break;


  case 504: /* function: "'function'"  */
  if (yyn == 504)
    /* "src/main/grammar/zend_language_parser.y":1410  */
                   { yyval = CG.zend_lineno; };
  break;


  case 505: /* backup_doc_comment: %empty  */
  if (yyn == 505)
    /* "src/main/grammar/zend_language_parser.y":1414  */
               { yyval = CG.doc_comment; CG.doc_comment = null; };
  break;


  case 506: /* backup_fn_flags: %empty  */
  if (yyn == 506)
    /* "src/main/grammar/zend_language_parser.y":1418  */
                                         { yyval = CG.extra_fn_flags; CG.extra_fn_flags = 0; };
  break;


  case 507: /* backup_lex_pos: %empty  */
  if (yyn == 507)
    /* "src/main/grammar/zend_language_parser.y":1422  */
               { yyval = LANG_SCNG.yy_text; };
  break;


  case 508: /* returns_ref: %empty  */
  if (yyn == 508)
    /* "src/main/grammar/zend_language_parser.y":1426  */
                        { yyval = 0; };
  break;


  case 509: /* returns_ref: ampersand  */
  if (yyn == 509)
    /* "src/main/grammar/zend_language_parser.y":1427  */
                                { yyval = ZEND_ACC_RETURN_REFERENCE; };
  break;


  case 510: /* lexical_vars: %empty  */
  if (yyn == 510)
    /* "src/main/grammar/zend_language_parser.y":1431  */
                       { yyval = null; };
  break;


  case 511: /* lexical_vars: "'use'" '(' lexical_var_list possible_comma ')'  */
  if (yyn == 511)
    /* "src/main/grammar/zend_language_parser.y":1432  */
                                                              { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 512: /* lexical_var_list: lexical_var_list ',' lexical_var  */
  if (yyn == 512)
    /* "src/main/grammar/zend_language_parser.y":1436  */
                                                 { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 513: /* lexical_var_list: lexical_var  */
  if (yyn == 513)
    /* "src/main/grammar/zend_language_parser.y":1437  */
                            { yyval = zend_ast_create_list(1, ZEND_AST_CLOSURE_USES, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 514: /* lexical_var: "variable"  */
  if (yyn == 514)
    /* "src/main/grammar/zend_language_parser.y":1441  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 515: /* lexical_var: ampersand "variable"  */
  if (yyn == 515)
    /* "src/main/grammar/zend_language_parser.y":1442  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); ((ZendAst) yyval).attr = ZEND_BIND_REF; };
  break;


  case 516: /* function_call: name argument_list  */
  if (yyn == 516)
    /* "src/main/grammar/zend_language_parser.y":1447  */
                        { yyval = zend_ast_create(ZEND_AST_CALL, ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 517: /* function_call: "'readonly'" argument_list  */
  if (yyn == 517)
    /* "src/main/grammar/zend_language_parser.y":1448  */
                                         {
			zval zv = new zval();
			if (zend_lex_tstring(zv, ((Integer)(yystack.valueAt (1)))) == FAILURE) { return YYABORT; }
			yyval = zend_ast_create(ZEND_AST_CALL, zend_ast_create_zval(zv), ((ZendAst)(yystack.valueAt (0))));
		};
  break;


  case 518: /* function_call: class_name "'::'" member_name argument_list  */
  if (yyn == 518)
    /* "src/main/grammar/zend_language_parser.y":1454  */
                        { yyval = zend_ast_create(ZEND_AST_STATIC_CALL, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 519: /* function_call: variable_class_name "'::'" member_name argument_list  */
  if (yyn == 519)
    /* "src/main/grammar/zend_language_parser.y":1456  */
                        { yyval = zend_ast_create(ZEND_AST_STATIC_CALL, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 520: /* @11: %empty  */
  if (yyn == 520)
    /* "src/main/grammar/zend_language_parser.y":1457  */
                              { yyval = CG.zend_lineno; };
  break;


  case 521: /* function_call: callable_expr @11 argument_list  */
  if (yyn == 521)
    /* "src/main/grammar/zend_language_parser.y":1457  */
                                                                              {
			yyval = zend_ast_create(ZEND_AST_CALL, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0))));
			((ZendAst) yyval).lineno = ((Integer)(yystack.valueAt (1)));
		};
  break;


  case 522: /* class_name: "'static'"  */
  if (yyn == 522)
    /* "src/main/grammar/zend_language_parser.y":1465  */
                        { zval zv = new zval(); ZVAL_INTERNED_STR(zv, ZSTR_KNOWN(ZEND_STR_STATIC));
			  yyval = zend_ast_create_zval_ex(zv, ZEND_NAME_NOT_FQ); };
  break;


  case 523: /* class_name: name  */
  if (yyn == 523)
    /* "src/main/grammar/zend_language_parser.y":1467  */
                     { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 524: /* class_name_reference: class_name  */
  if (yyn == 524)
    /* "src/main/grammar/zend_language_parser.y":1471  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 525: /* class_name_reference: new_variable  */
  if (yyn == 525)
    /* "src/main/grammar/zend_language_parser.y":1472  */
                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 526: /* class_name_reference: '(' expr ')'  */
  if (yyn == 526)
    /* "src/main/grammar/zend_language_parser.y":1473  */
                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 527: /* backticks_expr: %empty  */
  if (yyn == 527)
    /* "src/main/grammar/zend_language_parser.y":1478  */
                        { yyval = zend_ast_create_zval_from_str(ZSTR_EMPTY_ALLOC()); };
  break;


  case 528: /* backticks_expr: "string content"  */
  if (yyn == 528)
    /* "src/main/grammar/zend_language_parser.y":1479  */
                                          { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 529: /* backticks_expr: encaps_list  */
  if (yyn == 529)
    /* "src/main/grammar/zend_language_parser.y":1480  */
                            { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 530: /* ctor_arguments: %empty  */
  if (yyn == 530)
    /* "src/main/grammar/zend_language_parser.y":1485  */
                        { yyval = zend_ast_create_list(0, ZEND_AST_ARG_LIST); };
  break;


  case 531: /* ctor_arguments: argument_list  */
  if (yyn == 531)
    /* "src/main/grammar/zend_language_parser.y":1486  */
                              { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 532: /* dereferenceable_scalar: "'array'" '(' array_pair_list ')'  */
  if (yyn == 532)
    /* "src/main/grammar/zend_language_parser.y":1491  */
                                                { yyval = ((ZendAst)(yystack.valueAt (1))); ((ZendAst) yyval).attr = ZEND_ARRAY_SYNTAX_LONG; };
  break;


  case 533: /* dereferenceable_scalar: '[' array_pair_list ']'  */
  if (yyn == 533)
    /* "src/main/grammar/zend_language_parser.y":1492  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (1))); ((ZendAst) yyval).attr = ZEND_ARRAY_SYNTAX_SHORT; };
  break;


  case 534: /* dereferenceable_scalar: "quoted string"  */
  if (yyn == 534)
    /* "src/main/grammar/zend_language_parser.y":1493  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 535: /* dereferenceable_scalar: '"' encaps_list '"'  */
  if (yyn == 535)
    /* "src/main/grammar/zend_language_parser.y":1494  */
                                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 536: /* scalar: "integer"  */
  if (yyn == 536)
    /* "src/main/grammar/zend_language_parser.y":1498  */
                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 537: /* scalar: "floating-point number"  */
  if (yyn == 537)
    /* "src/main/grammar/zend_language_parser.y":1499  */
                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 538: /* scalar: "heredoc start" "string content" "heredoc end"  */
  if (yyn == 538)
    /* "src/main/grammar/zend_language_parser.y":1500  */
                                                                        { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 539: /* scalar: "heredoc start" "heredoc end"  */
  if (yyn == 539)
    /* "src/main/grammar/zend_language_parser.y":1502  */
                        { yyval = zend_ast_create_zval_from_str(ZSTR_EMPTY_ALLOC()); };
  break;


  case 540: /* scalar: "heredoc start" encaps_list "heredoc end"  */
  if (yyn == 540)
    /* "src/main/grammar/zend_language_parser.y":1503  */
                                                          { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 541: /* scalar: dereferenceable_scalar  */
  if (yyn == 541)
    /* "src/main/grammar/zend_language_parser.y":1504  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 542: /* scalar: constant  */
  if (yyn == 542)
    /* "src/main/grammar/zend_language_parser.y":1505  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 543: /* scalar: class_constant  */
  if (yyn == 543)
    /* "src/main/grammar/zend_language_parser.y":1506  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 544: /* constant: name  */
  if (yyn == 544)
    /* "src/main/grammar/zend_language_parser.y":1510  */
                                { yyval = zend_ast_create(ZEND_AST_CONST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 545: /* constant: "'__LINE__'"  */
  if (yyn == 545)
    /* "src/main/grammar/zend_language_parser.y":1511  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_LINE); };
  break;


  case 546: /* constant: "'__FILE__'"  */
  if (yyn == 546)
    /* "src/main/grammar/zend_language_parser.y":1512  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_FILE); };
  break;


  case 547: /* constant: "'__DIR__'"  */
  if (yyn == 547)
    /* "src/main/grammar/zend_language_parser.y":1513  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_DIR); };
  break;


  case 548: /* constant: "'__TRAIT__'"  */
  if (yyn == 548)
    /* "src/main/grammar/zend_language_parser.y":1514  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_TRAIT_C); };
  break;


  case 549: /* constant: "'__METHOD__'"  */
  if (yyn == 549)
    /* "src/main/grammar/zend_language_parser.y":1515  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_METHOD_C); };
  break;


  case 550: /* constant: "'__FUNCTION__'"  */
  if (yyn == 550)
    /* "src/main/grammar/zend_language_parser.y":1516  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_FUNC_C); };
  break;


  case 551: /* constant: "'__PROPERTY__'"  */
  if (yyn == 551)
    /* "src/main/grammar/zend_language_parser.y":1517  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_PROPERTY_C); };
  break;


  case 552: /* constant: "'__NAMESPACE__'"  */
  if (yyn == 552)
    /* "src/main/grammar/zend_language_parser.y":1518  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_NS_C); };
  break;


  case 553: /* constant: "'__CLASS__'"  */
  if (yyn == 553)
    /* "src/main/grammar/zend_language_parser.y":1519  */
                                { yyval = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_CLASS_C); };
  break;


  case 554: /* class_constant: class_name "'::'" identifier  */
  if (yyn == 554)
    /* "src/main/grammar/zend_language_parser.y":1524  */
                        { yyval = zend_ast_create_class_const_or_name(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 555: /* class_constant: variable_class_name "'::'" identifier  */
  if (yyn == 555)
    /* "src/main/grammar/zend_language_parser.y":1526  */
                        { yyval = zend_ast_create_class_const_or_name(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 556: /* class_constant: class_name "'::'" '{' expr '}'  */
  if (yyn == 556)
    /* "src/main/grammar/zend_language_parser.y":1528  */
                        { yyval = zend_ast_create(ZEND_AST_CLASS_CONST, ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 557: /* class_constant: variable_class_name "'::'" '{' expr '}'  */
  if (yyn == 557)
    /* "src/main/grammar/zend_language_parser.y":1530  */
                        { yyval = zend_ast_create(ZEND_AST_CLASS_CONST, ((ZendAst)(yystack.valueAt (4))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 558: /* optional_expr: %empty  */
  if (yyn == 558)
    /* "src/main/grammar/zend_language_parser.y":1534  */
                        { yyval = null; };
  break;


  case 559: /* optional_expr: expr  */
  if (yyn == 559)
    /* "src/main/grammar/zend_language_parser.y":1535  */
                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 560: /* variable_class_name: fully_dereferenceable  */
  if (yyn == 560)
    /* "src/main/grammar/zend_language_parser.y":1539  */
                                      { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 561: /* fully_dereferenceable: variable  */
  if (yyn == 561)
    /* "src/main/grammar/zend_language_parser.y":1543  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 562: /* fully_dereferenceable: '(' expr ')'  */
  if (yyn == 562)
    /* "src/main/grammar/zend_language_parser.y":1544  */
                             {
			yyval = ((ZendAst)(yystack.valueAt (1)));
			if (((ZendAst) yyval).kind == ZEND_AST_STATIC_PROP) ((ZendAst) yyval).attr = ZEND_PARENTHESIZED_STATIC_PROP;
		};
  break;


  case 563: /* fully_dereferenceable: dereferenceable_scalar  */
  if (yyn == 563)
    /* "src/main/grammar/zend_language_parser.y":1548  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 564: /* fully_dereferenceable: class_constant  */
  if (yyn == 564)
    /* "src/main/grammar/zend_language_parser.y":1549  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


        default: break;
      }
    this.yyval = yyval;
    return YYNEWSTATE;
  }

  private int yyaction_4(int yyn, YYStack yystack)
  {
    Object yyval = this.yyval;
    switch (yyn)
      {
  case 565: /* fully_dereferenceable: new_dereferenceable  */
  if (yyn == 565)
    /* "src/main/grammar/zend_language_parser.y":1550  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 566: /* array_object_dereferenceable: fully_dereferenceable  */
  if (yyn == 566)
    /* "src/main/grammar/zend_language_parser.y":1554  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 567: /* array_object_dereferenceable: constant  */
  if (yyn == 567)
    /* "src/main/grammar/zend_language_parser.y":1555  */
                                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 568: /* callable_expr: callable_variable  */
  if (yyn == 568)
    /* "src/main/grammar/zend_language_parser.y":1559  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 569: /* callable_expr: '(' expr ')'  */
  if (yyn == 569)
    /* "src/main/grammar/zend_language_parser.y":1560  */
                                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 570: /* callable_expr: dereferenceable_scalar  */
  if (yyn == 570)
    /* "src/main/grammar/zend_language_parser.y":1561  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 571: /* callable_expr: new_dereferenceable  */
  if (yyn == 571)
    /* "src/main/grammar/zend_language_parser.y":1562  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 572: /* callable_variable: simple_variable  */
  if (yyn == 572)
    /* "src/main/grammar/zend_language_parser.y":1567  */
                        { yyval = zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 573: /* callable_variable: array_object_dereferenceable '[' optional_expr ']'  */
  if (yyn == 573)
    /* "src/main/grammar/zend_language_parser.y":1569  */
                        { yyval = zend_ast_create(ZEND_AST_DIM, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 574: /* callable_variable: array_object_dereferenceable "'->'" property_name argument_list  */
  if (yyn == 574)
    /* "src/main/grammar/zend_language_parser.y":1571  */
                        { yyval = zend_ast_create(ZEND_AST_METHOD_CALL, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 575: /* callable_variable: array_object_dereferenceable "'?->'" property_name argument_list  */
  if (yyn == 575)
    /* "src/main/grammar/zend_language_parser.y":1573  */
                        { yyval = zend_ast_create(ZEND_AST_NULLSAFE_METHOD_CALL, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 576: /* callable_variable: function_call  */
  if (yyn == 576)
    /* "src/main/grammar/zend_language_parser.y":1574  */
                              { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 577: /* variable: callable_variable  */
  if (yyn == 577)
    /* "src/main/grammar/zend_language_parser.y":1579  */
                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 578: /* variable: static_member  */
  if (yyn == 578)
    /* "src/main/grammar/zend_language_parser.y":1581  */
                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 579: /* variable: array_object_dereferenceable "'->'" property_name  */
  if (yyn == 579)
    /* "src/main/grammar/zend_language_parser.y":1583  */
                        { yyval = zend_ast_create(ZEND_AST_PROP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 580: /* variable: array_object_dereferenceable "'?->'" property_name  */
  if (yyn == 580)
    /* "src/main/grammar/zend_language_parser.y":1585  */
                        { yyval = zend_ast_create(ZEND_AST_NULLSAFE_PROP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 581: /* simple_variable: "variable"  */
  if (yyn == 581)
    /* "src/main/grammar/zend_language_parser.y":1589  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 582: /* simple_variable: '$' '{' expr '}'  */
  if (yyn == 582)
    /* "src/main/grammar/zend_language_parser.y":1590  */
                                        { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 583: /* simple_variable: '$' simple_variable  */
  if (yyn == 583)
    /* "src/main/grammar/zend_language_parser.y":1591  */
                                        { yyval = zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 584: /* static_member: class_name "'::'" simple_variable  */
  if (yyn == 584)
    /* "src/main/grammar/zend_language_parser.y":1596  */
                        { yyval = zend_ast_create(ZEND_AST_STATIC_PROP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 585: /* static_member: variable_class_name "'::'" simple_variable  */
  if (yyn == 585)
    /* "src/main/grammar/zend_language_parser.y":1598  */
                        { yyval = zend_ast_create(ZEND_AST_STATIC_PROP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 586: /* new_variable: simple_variable  */
  if (yyn == 586)
    /* "src/main/grammar/zend_language_parser.y":1603  */
                        { yyval = zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 587: /* new_variable: new_variable '[' optional_expr ']'  */
  if (yyn == 587)
    /* "src/main/grammar/zend_language_parser.y":1605  */
                        { yyval = zend_ast_create(ZEND_AST_DIM, ((ZendAst)(yystack.valueAt (3))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 588: /* new_variable: new_variable "'->'" property_name  */
  if (yyn == 588)
    /* "src/main/grammar/zend_language_parser.y":1607  */
                        { yyval = zend_ast_create(ZEND_AST_PROP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 589: /* new_variable: new_variable "'?->'" property_name  */
  if (yyn == 589)
    /* "src/main/grammar/zend_language_parser.y":1609  */
                        { yyval = zend_ast_create(ZEND_AST_NULLSAFE_PROP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 590: /* new_variable: class_name "'::'" simple_variable  */
  if (yyn == 590)
    /* "src/main/grammar/zend_language_parser.y":1611  */
                        { yyval = zend_ast_create(ZEND_AST_STATIC_PROP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 591: /* new_variable: new_variable "'::'" simple_variable  */
  if (yyn == 591)
    /* "src/main/grammar/zend_language_parser.y":1613  */
                        { yyval = zend_ast_create(ZEND_AST_STATIC_PROP, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 592: /* member_name: identifier  */
  if (yyn == 592)
    /* "src/main/grammar/zend_language_parser.y":1617  */
                           { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 593: /* member_name: '{' expr '}'  */
  if (yyn == 593)
    /* "src/main/grammar/zend_language_parser.y":1618  */
                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 594: /* member_name: simple_variable  */
  if (yyn == 594)
    /* "src/main/grammar/zend_language_parser.y":1619  */
                                { yyval = zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 595: /* property_name: "identifier"  */
  if (yyn == 595)
    /* "src/main/grammar/zend_language_parser.y":1623  */
                         { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 596: /* property_name: '{' expr '}'  */
  if (yyn == 596)
    /* "src/main/grammar/zend_language_parser.y":1624  */
                                { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 597: /* property_name: simple_variable  */
  if (yyn == 597)
    /* "src/main/grammar/zend_language_parser.y":1625  */
                                { yyval = zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 598: /* array_pair_list: non_empty_array_pair_list  */
  if (yyn == 598)
    /* "src/main/grammar/zend_language_parser.y":1630  */
                        { /* allow single trailing comma */ yyval = zend_ast_list_rtrim(((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 599: /* possible_array_pair: %empty  */
  if (yyn == 599)
    /* "src/main/grammar/zend_language_parser.y":1634  */
                       { yyval = null; };
  break;


  case 600: /* possible_array_pair: array_pair  */
  if (yyn == 600)
    /* "src/main/grammar/zend_language_parser.y":1635  */
                            { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 601: /* non_empty_array_pair_list: non_empty_array_pair_list ',' possible_array_pair  */
  if (yyn == 601)
    /* "src/main/grammar/zend_language_parser.y":1640  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 602: /* non_empty_array_pair_list: possible_array_pair  */
  if (yyn == 602)
    /* "src/main/grammar/zend_language_parser.y":1642  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_ARRAY, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 603: /* array_pair: expr "'=>'" expr  */
  if (yyn == 603)
    /* "src/main/grammar/zend_language_parser.y":1647  */
                        { yyval = zend_ast_create(ZEND_AST_ARRAY_ELEM, ((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (2)))); };
  break;


  case 604: /* array_pair: expr  */
  if (yyn == 604)
    /* "src/main/grammar/zend_language_parser.y":1649  */
                        { yyval = zend_ast_create(ZEND_AST_ARRAY_ELEM, ((ZendAst)(yystack.valueAt (0))), null); };
  break;


  case 605: /* array_pair: expr "'=>'" ampersand variable  */
  if (yyn == 605)
    /* "src/main/grammar/zend_language_parser.y":1651  */
                        { yyval = zend_ast_create_ex(ZEND_AST_ARRAY_ELEM, 1, ((ZendAst)(yystack.valueAt (0))), ((ZendAst)(yystack.valueAt (3)))); };
  break;


  case 606: /* array_pair: ampersand variable  */
  if (yyn == 606)
    /* "src/main/grammar/zend_language_parser.y":1653  */
                        { yyval = zend_ast_create_ex(ZEND_AST_ARRAY_ELEM, 1, ((ZendAst)(yystack.valueAt (0))), null); };
  break;


  case 607: /* array_pair: "'...'" expr  */
  if (yyn == 607)
    /* "src/main/grammar/zend_language_parser.y":1655  */
                        { yyval = zend_ast_create(ZEND_AST_UNPACK, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 608: /* array_pair: expr "'=>'" "'list'" '(' array_pair_list ')'  */
  if (yyn == 608)
    /* "src/main/grammar/zend_language_parser.y":1657  */
                        { ((ZendAst)(yystack.valueAt (1))).attr = ZEND_ARRAY_SYNTAX_LIST;
			  yyval = zend_ast_create(ZEND_AST_ARRAY_ELEM, ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (5)))); };
  break;


  case 609: /* array_pair: "'list'" '(' array_pair_list ')'  */
  if (yyn == 609)
    /* "src/main/grammar/zend_language_parser.y":1660  */
                        { ((ZendAst)(yystack.valueAt (1))).attr = ZEND_ARRAY_SYNTAX_LIST;
			  yyval = zend_ast_create(ZEND_AST_ARRAY_ELEM, ((ZendAst)(yystack.valueAt (1))), null); };
  break;


  case 610: /* encaps_list: encaps_list encaps_var  */
  if (yyn == 610)
    /* "src/main/grammar/zend_language_parser.y":1666  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 611: /* encaps_list: encaps_list "string content"  */
  if (yyn == 611)
    /* "src/main/grammar/zend_language_parser.y":1668  */
                        { yyval = zend_ast_list_add(((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 612: /* encaps_list: encaps_var  */
  if (yyn == 612)
    /* "src/main/grammar/zend_language_parser.y":1670  */
                        { yyval = zend_ast_create_list(1, ZEND_AST_ENCAPS_LIST, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 613: /* encaps_list: "string content" encaps_var  */
  if (yyn == 613)
    /* "src/main/grammar/zend_language_parser.y":1672  */
                        { yyval = zend_ast_create_list(2, ZEND_AST_ENCAPS_LIST, ((ZendAst)(yystack.valueAt (1))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 614: /* encaps_var: "variable"  */
  if (yyn == 614)
    /* "src/main/grammar/zend_language_parser.y":1677  */
                        { yyval = zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 615: /* encaps_var: "variable" '[' encaps_var_offset ']'  */
  if (yyn == 615)
    /* "src/main/grammar/zend_language_parser.y":1679  */
                        { yyval = zend_ast_create(ZEND_AST_DIM,
			      zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (3)))), ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 616: /* encaps_var: "variable" "'->'" "identifier"  */
  if (yyn == 616)
    /* "src/main/grammar/zend_language_parser.y":1682  */
                        { yyval = zend_ast_create(ZEND_AST_PROP,
			      zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (2)))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 617: /* encaps_var: "variable" "'?->'" "identifier"  */
  if (yyn == 617)
    /* "src/main/grammar/zend_language_parser.y":1685  */
                        { yyval = zend_ast_create(ZEND_AST_NULLSAFE_PROP,
			      zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (2)))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 618: /* encaps_var: "'${'" expr '}'  */
  if (yyn == 618)
    /* "src/main/grammar/zend_language_parser.y":1688  */
                        { yyval = zend_ast_create_ex(ZEND_AST_VAR, ZEND_ENCAPS_VAR_DOLLAR_CURLY_VAR_VAR, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 619: /* encaps_var: "'${'" "variable name" '}'  */
  if (yyn == 619)
    /* "src/main/grammar/zend_language_parser.y":1690  */
                        { yyval = zend_ast_create_ex(ZEND_AST_VAR, ZEND_ENCAPS_VAR_DOLLAR_CURLY, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 620: /* encaps_var: "'${'" "variable name" '[' expr ']' '}'  */
  if (yyn == 620)
    /* "src/main/grammar/zend_language_parser.y":1692  */
                        { yyval = zend_ast_create_ex(ZEND_AST_DIM, ZEND_ENCAPS_VAR_DOLLAR_CURLY,
			      zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (4)))), ((ZendAst)(yystack.valueAt (2)))); };
  break;


  case 621: /* encaps_var: "'{$'" variable '}'  */
  if (yyn == 621)
    /* "src/main/grammar/zend_language_parser.y":1694  */
                                          { yyval = ((ZendAst)(yystack.valueAt (1))); };
  break;


  case 622: /* encaps_var_offset: "identifier"  */
  if (yyn == 622)
    /* "src/main/grammar/zend_language_parser.y":1698  */
                                                { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 623: /* encaps_var_offset: "number"  */
  if (yyn == 623)
    /* "src/main/grammar/zend_language_parser.y":1699  */
                                        { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 624: /* encaps_var_offset: '-' "number"  */
  if (yyn == 624)
    /* "src/main/grammar/zend_language_parser.y":1700  */
                                        { yyval = zend_negate_num_string(((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 625: /* encaps_var_offset: "variable"  */
  if (yyn == 625)
    /* "src/main/grammar/zend_language_parser.y":1701  */
                                                { yyval = zend_ast_create(ZEND_AST_VAR, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 626: /* internal_functions_in_yacc: "'isset'" '(' isset_variables possible_comma ')'  */
  if (yyn == 626)
    /* "src/main/grammar/zend_language_parser.y":1706  */
                                                               { yyval = ((ZendAst)(yystack.valueAt (2))); };
  break;


  case 627: /* internal_functions_in_yacc: "'empty'" '(' expr ')'  */
  if (yyn == 627)
    /* "src/main/grammar/zend_language_parser.y":1707  */
                                     { yyval = zend_ast_create(ZEND_AST_EMPTY, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 628: /* internal_functions_in_yacc: "'include'" expr  */
  if (yyn == 628)
    /* "src/main/grammar/zend_language_parser.y":1709  */
                        { yyval = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_INCLUDE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 629: /* internal_functions_in_yacc: "'include_once'" expr  */
  if (yyn == 629)
    /* "src/main/grammar/zend_language_parser.y":1711  */
                        { yyval = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_INCLUDE_ONCE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 630: /* internal_functions_in_yacc: "'eval'" '(' expr ')'  */
  if (yyn == 630)
    /* "src/main/grammar/zend_language_parser.y":1713  */
                        { yyval = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_EVAL, ((ZendAst)(yystack.valueAt (1)))); };
  break;


  case 631: /* internal_functions_in_yacc: "'require'" expr  */
  if (yyn == 631)
    /* "src/main/grammar/zend_language_parser.y":1715  */
                        { yyval = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_REQUIRE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 632: /* internal_functions_in_yacc: "'require_once'" expr  */
  if (yyn == 632)
    /* "src/main/grammar/zend_language_parser.y":1717  */
                        { yyval = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_REQUIRE_ONCE, ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 633: /* isset_variables: isset_variable  */
  if (yyn == 633)
    /* "src/main/grammar/zend_language_parser.y":1721  */
                               { yyval = ((ZendAst)(yystack.valueAt (0))); };
  break;


  case 634: /* isset_variables: isset_variables ',' isset_variable  */
  if (yyn == 634)
    /* "src/main/grammar/zend_language_parser.y":1723  */
                        { yyval = zend_ast_create(ZEND_AST_AND, ((ZendAst)(yystack.valueAt (2))), ((ZendAst)(yystack.valueAt (0)))); };
  break;


  case 635: /* isset_variable: expr  */
  if (yyn == 635)
    /* "src/main/grammar/zend_language_parser.y":1727  */
                     { yyval = zend_ast_create(ZEND_AST_ISSET, ((ZendAst)(yystack.valueAt (0)))); };
  break;



/* "src/main/java/pl/codeloom/php/parser/zend/generated/ZendLanguageParser.java":5435  */


        default: break;
      }
    this.yyval = yyval;
    return YYNEWSTATE;
  }




  /**
   * Parse input from the scanner that was specified at object construction
   * time.  Return whether the end of the input was reached successfully.
   *
   * @return <tt>true</tt> if the parsing succeeds.  Note that this does not
   *          imply that there were no syntax errors.
   */
  public boolean parse() throws java.io.IOException

  {


    /* Lookahead token kind.  */
    int yychar = YYEMPTY_;
    /* Lookahead symbol kind.  */
    SymbolKind yytoken = null;

    /* State.  */
    int yyn = 0;
    int yylen = 0;
    int yystate = 0;
    YYStack yystack = new YYStack ();
    int label = YYNEWSTATE;



    /* Semantic value of the lookahead.  */
    Object yylval = null;



    yyerrstatus_ = 0;
    yynerrs = 0;

    /* Initialize the stack.  */
    yystack.push (yystate, yylval);



    for (;;)
      switch (label)
      {
        /* New state.  Unlike in the C/C++ skeletons, the state is already
           pushed when we come here.  */
      case YYNEWSTATE:

        /* Accept?  */
        if (yystate == YYFINAL_)
          return true;

        /* Take a decision.  First try without lookahead.  */
        yyn = yypact_[yystate];
        if (yyPactValueIsDefault (yyn))
          {
            label = YYDEFAULT;
            break;
          }

        /* Read a lookahead token.  */
        if (yychar == YYEMPTY_)
          {

            yychar = yylexer.yylex ();
            yylval = yylexer.getLVal();

          }

        /* Convert token to internal form.  */
        yytoken = yytranslate_ (yychar);

        if (yytoken == SymbolKind.S_YYerror)
          {
            // The scanner already issued an error message, process directly
            // to error recovery.  But do not keep the error token as
            // lookahead, it is too special and may lead us to an endless
            // loop in error recovery. */
            yychar = Lexer.YYUNDEF;
            yytoken = SymbolKind.S_YYUNDEF;
            label = YYERRLAB1;
          }
        else
          {
            /* If the proper action on seeing token YYTOKEN is to reduce or to
               detect an error, take that action.  */
            yyn += yytoken.getCode();
            if (yyn < 0 || YYLAST_ < yyn || yycheck_[yyn] != yytoken.getCode()) {
              label = YYDEFAULT;
            }

            /* <= 0 means reduce or error.  */
            else if ((yyn = yytable_[yyn]) <= 0)
              {
                if (yyTableValueIsError(yyn)) {
                  label = YYERRLAB;
                } else {
                  yyn = -yyn;
                  label = YYREDUCE;
                }
              }

            else
              {
                /* Shift the lookahead token.  */
                /* Discard the token being shifted.  */
                yychar = YYEMPTY_;

                /* Count tokens shifted since error; after three, turn off error
                   status.  */
                if (yyerrstatus_ > 0)
                  --yyerrstatus_;

                yystate = yyn;
                yystack.push(yystate, yylval);
                label = YYNEWSTATE;
              }
          }
        break;

      /*-----------------------------------------------------------.
      | yydefault -- do the default action for the current state.  |
      `-----------------------------------------------------------*/
      case YYDEFAULT:
        yyn = yydefact_[yystate];
        if (yyn == 0)
          label = YYERRLAB;
        else
          label = YYREDUCE;
        break;

      /*-----------------------------.
      | yyreduce -- Do a reduction.  |
      `-----------------------------*/
      case YYREDUCE:
        yylen = yyr2_[yyn];
        label = yyaction(yyn, yystack, yylen);
        yystate = yystack.stateAt(0);
        break;

      /*------------------------------------.
      | yyerrlab -- here on detecting error |
      `------------------------------------*/
      case YYERRLAB:
        /* If not already recovering from an error, report this error.  */
        if (yyerrstatus_ == 0)
          {
            ++yynerrs;
            if (yychar == YYEMPTY_)
              yytoken = null;
            yyreportSyntaxError(new Context(this, yystack, yytoken));
          }

        if (yyerrstatus_ == 3)
          {
            /* If just tried and failed to reuse lookahead token after an
               error, discard it.  */

            if (yychar <= Lexer.END)
              {
                /* Return failure if at end of input.  */
                if (yychar == Lexer.END)
                  return false;
              }
            else
              yychar = YYEMPTY_;
          }

        /* Else will try to reuse lookahead token after shifting the error
           token.  */
        label = YYERRLAB1;
        break;

      /*-------------------------------------------------.
      | errorlab -- error raised explicitly by YYERROR.  |
      `-------------------------------------------------*/
      case YYERROR:
        /* Do not reclaim the symbols of the rule which action triggered
           this YYERROR.  */
        yystack.pop (yylen);
        yylen = 0;
        yystate = yystack.stateAt(0);
        label = YYERRLAB1;
        break;

      /*-------------------------------------------------------------.
      | yyerrlab1 -- common code for both syntax error and YYERROR.  |
      `-------------------------------------------------------------*/
      case YYERRLAB1:
        yyerrstatus_ = 3;       /* Each real token shifted decrements this.  */

        // Pop stack until we find a state that shifts the error token.
        for (;;)
          {
            yyn = yypact_[yystate];
            if (!yyPactValueIsDefault (yyn))
              {
                yyn += SymbolKind.S_YYerror.getCode();
                if (0 <= yyn && yyn <= YYLAST_
                    && yycheck_[yyn] == SymbolKind.S_YYerror.getCode())
                  {
                    yyn = yytable_[yyn];
                    if (0 < yyn)
                      break;
                  }
              }

            /* Pop the current state because it cannot handle the
             * error token.  */
            if (yystack.height == 0)
              return false;


            yystack.pop ();
            yystate = yystack.stateAt(0);
          }

        if (label == YYABORT)
          /* Leave the switch.  */
          break;



        /* Shift the error token.  */

        yystate = yyn;
        yystack.push (yyn, yylval);
        label = YYNEWSTATE;
        break;

        /* Accept.  */
      case YYACCEPT:
        return true;

        /* Abort.  */
      case YYABORT:
        return false;
      }
}




  /**
   * Information needed to get the list of expected tokens and to forge
   * a syntax error diagnostic.
   */
  public static final class Context {
    Context(ZendLanguageParser parser, YYStack stack, SymbolKind token) {
      yyparser = parser;
      yystack = stack;
      yytoken = token;
    }

    private ZendLanguageParser yyparser;
    private YYStack yystack;


    /**
     * The symbol kind of the lookahead token.
     */
    public final SymbolKind getToken() {
      return yytoken;
    }

    private SymbolKind yytoken;
    static final int NTOKENS = ZendLanguageParser.YYNTOKENS_;

    /**
     * Put in YYARG at most YYARGN of the expected tokens given the
     * current YYCTX, and return the number of tokens stored in YYARG.  If
     * YYARG is null, return the number of expected tokens (guaranteed to
     * be less than YYNTOKENS).
     */
    public int getExpectedTokens(SymbolKind yyarg[], int yyargn) {
      return getExpectedTokens (yyarg, 0, yyargn);
    }

    public int getExpectedTokens(SymbolKind yyarg[], int yyoffset, int yyargn) {
      int yycount = yyoffset;
      int yyn = yypact_[this.yystack.stateAt(0)];
      if (!yyPactValueIsDefault(yyn))
        {
          /* Start YYX at -YYN if negative to avoid negative
             indexes in YYCHECK.  In other words, skip the first
             -YYN actions for this state because they are default
             actions.  */
          int yyxbegin = yyn < 0 ? -yyn : 0;
          /* Stay within bounds of both yycheck and yytname.  */
          int yychecklim = YYLAST_ - yyn + 1;
          int yyxend = yychecklim < NTOKENS ? yychecklim : NTOKENS;
          for (int yyx = yyxbegin; yyx < yyxend; ++yyx)
            if (yycheck_[yyx + yyn] == yyx && yyx != SymbolKind.S_YYerror.getCode()
                && !yyTableValueIsError(yytable_[yyx + yyn]))
              {
                if (yyarg == null)
                  yycount += 1;
                else if (yycount == yyargn)
                  return 0; // FIXME: this is incorrect.
                else
                  yyarg[yycount++] = SymbolKind.get(yyx);
              }
        }
      if (yyarg != null && yycount == yyoffset && yyoffset < yyargn)
        yyarg[yycount] = null;
      return yycount - yyoffset;
    }
  }





  /**
   * Build and emit a "syntax error" message in a user-defined way.
   *
   * @param ctx  The context of the error.
   */
  private void yyreportSyntaxError(Context yyctx) {
      yylexer.reportSyntaxError(yyctx);
  }

  /**
   * Whether the given <code>yypact_</code> value indicates a defaulted state.
   * @param yyvalue   the value to check
   */
  private static boolean yyPactValueIsDefault(int yyvalue) {
    return yyvalue == yypact_ninf_;
  }

  /**
   * Whether the given <code>yytable_</code>
   * value indicates a syntax error.
   * @param yyvalue the value to check
   */
  private static boolean yyTableValueIsError(int yyvalue) {
    return yyvalue == yytable_ninf_;
  }

  private static final short yypact_ninf_ = -937;
  private static final short yytable_ninf_ = -607;

/* YYPACT[STATE-NUM] -- Index in YYTABLE of the portion describing
   STATE-NUM.  */
  private static final short[] yypact_ = yypact_init();
  private static final short[] yypact_init()
  {
    return new short[]
    {
    -937,   114,  4573,  -937,  8991,  8991,  8991,  8991,  8991,  -937,
    -937,   167,  -937,  -937,  -937,  -937,  -937,  -937,  8991,  8991,
      12,  8991,  8991,  8991,  8991,  8991,   456,  9164,    17,    87,
    8991,  7261,   101,   129,   140,   156,   188,   227,  8991,  8991,
     407,  -937,  -937,   412,  8991,   266,  8991,   475,    29,   226,
    -937,  -937,    17,   265,   275,   284,   290,  -937,  -937,  -937,
    -937, 11016,   313,   319,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937, 10436, 10436,  8991,  8991,  8991,  8991,
    8991,  8991,  8991,  8991,    37,  -937,  8991,  -937,  7434,    90,
      99,    16,   -39,  -937,   806,  -937,  -937,  -937,  -937,  -937,
    -937,   535,  -937,  -937,  -937,  -937,  -937,   298,  -937,   305,
    -937,   355,  -937,  9324,  -937,   256,   256,  -937,   346,   360,
    -937,   454,   521,   359,   364,   545,  -937,   337,  1123,  -937,
    -937,  -937,  -937,   487,    17,   252,   367,   256,   367,    15,
     367,   367,  -937,  9824,  9824,  8991,  9824,  9824, 10026,  9971,
   10026,  -937,  -937,  8991,  -937,   429,    -5,   434,  -937,  -937,
     391,    17,  -937,   549,  3535,  -937,  -937,  3708,  -937,  -937,
    8991,   236,  -937,  9824,   515,  8991,  7953,  8991,   412,  8991,
    8991,  9824,   418,   421,   426,   611,   368,  -937,   461,  -937,
    9824,  -937,  -937,  -937,  -937,  -937,  -937,    -4,   723,   476,
     438,  -937,   449,  -937,  -937,   660,   460,  -937,  -937,  -937,
   10436,  8991,  8991,   503,   662,   668,   675,   695,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,   551,   542,  7434,  7434,   327,   456,  8991,  7434,   556,
     562,  -937,  -937,   587,   587,   367,   367,   367,   367,   367,
     367,   367,  9344,   559,   130,  -937,  8126, 10436,   147,  -937,
    2874,  4746,   565,  8991,  -937,  -937, 10436,  9722,   561,  -937,
     571,  -937,    77,   584,   139,    77,    93,  8991,  -937,  -937,
     487,  -937,  -937,  -937,  -937,  -937,   576,  7261,   599,   772,
     609,  2238,  8991,  8991,  8991,  8991,  8991,  8991,  8991,  8991,
    8991,  8991,  8991,  8991,  8991,   110,  8991,  8991,  8991,  8991,
    8991,  8991,  8991,  8991,  8991,  8991,  8991,  8991,  8991,  8991,
    8991,  8991,  -937,  -937,  -937,   142, 10178, 10331,    89,    89,
    8991,    17,  7607,  8991,  8991,  8991,  8991,  8991,  8991,  8991,
    8991,  8991,  8991,  8991,  8991,  -937,  -937,  8991,  -937,  2887,
    8991,  2942,  -937,  -937,  -937,    29,  -937,    89,    89,    29,
    8991,   121,  8991,  8991,    12,  8991,  8991,  8991,  2411,   731,
    9164,     7,   227,   780,   782,  8991,   169,    17,   275,   284,
     313,   319,   783,   787,   788,   794,   796,   798,   799,   800,
     801,  8299,  -937,   802,   637,  -937,  2805,  8472,  -937,   638,
    -937,  -937,  9824,  3022,  8991,  -937,   642,  3077,  8991,   644,
     647,  9824,  9765,   310,  3090,  3157,  -937,  -937,  -937,  8991,
     412,  -937,  -937,  4919,   803,   646,    13,   651,   468,  -937,
     723,  -937,    29,  -937,  8991,   805,  -937,   649,  -937,   329,
    9824,   655,  -937,  3225,   654,   737,  -937,   739,   842,  -937,
     670,  -937,   672,   673,  -937,   687,    17,    17,  3292,   683,
    -937,   839,   841,   764,  -937,  -937,   489,  2523,   685,  -937,
    -937,  -937,   498,   689,  -937,   869,  -937,  -937,  -937,  7434,
    9824,   351,  7780,   863,  7434,  -937,  -937,  2591,  -937,   845,
    8991,  -937,  8991,  -937,  -937,  8991,  9777,  3868,  4041,   964,
     964,   267,    95,    95,    15,    15,    15,  9959, 10014, 10026,
    -937,  1355,  3695,  4214,  4214,  4214,  4214,   964,   964,  4214,
     128,   128, 10037,   367,   136,  8286,  8286,   692,  -937,  -937,
    -937,   694,  8991,   697,   698,    17,  8991,   697,   698,    17,
    -937,  8991,  -937,    17,    17,   701,  -937, 10436, 10026, 10026,
   10026, 10026, 10026, 10026, 10026, 10026, 10026, 10026, 10026, 10026,
   10026, 10026,  -937, 10026,  -937,    17,  -937,  -937,  -937,  -937,
     707,  -937,  9824,  8991,  4054,   705,  3881,  -937,  4054,   706,
    1368,  -937,  8991,  1542,  9824,  7953,  8645, 10425,  -937,    22,
     708,  9824,  -937,  -937,  -937,   431,   712,  -937,  -937,   823,
    -937,  -937,  9824,  -937, 10436,   715,  8991,   716,  -937,  -937,
     327,   807,   719,   327,  -937,    48,   807,  -937,  4227,   896,
    -937,   327,   726,  -937,   725,  -937,  -937,  -937,   875,  -937,
    -937,  -937,   735,  -937,  8991,  -937,  -937,   736,  -937,   738,
     734, 10436,  9824,  8991,  -937,  -937,   737,  3305,  3360,  5092,
   10037,  8991,  1094,   740,  1094,  2603,  -937,  2658,  -937,  2670,
    -937,  -937,  -937,   587,   737,  -937,  9824,  8991,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  -937,  3373,  -937,  -937,  -937,
     742,   746,  9573,  8991,  9824,   744,  7434, 10436,   -82,    92,
    1716,   747,   750,  -937,  8818,  -937,   600,   851,   263,   753,
    -937,  -937,   263,  -937,   752,  -937,  -937,  -937,   327,  -937,
    -937,   755,  -937,   754,   583,  -937,  -937,  -937,   583,  -937,
    -937,    47,   925,   931,   768,  -937,  -937,  4400,  -937,  8991,
    -937,  -937,  -937,  -937,  9526,   767,   896,  7434,   446, 10026,
     807,  7261,   939,   770, 10037,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,  1587,   769,   775,  -937,    57,
    -937,  1622,  -937,  1094,   779,   773,   773,  -937,   807,  6995,
     785,  5265,  7953,  8645,  9824,  7434,   784,   179, 10425,  1890,
    -937,  -937,  -937,  -937,   514,  -937,    36,   789,   778,   790,
    -937,   808,  9824,   793,   810,  -937,   949,  -937,   431,   804,
     813,  -937,  -937,   755,   811,  1243,   327,  -937,  -937,   791,
      34,   583,   451,   451,   583,   814,  -937, 10026,   816,  -937,
     820,  -937,  -937,  -937,  -937,  -937,   975,  1577,  -937,   302,
     302,   824,  -937,    56,   992,   994,   831,  -937,   826,   926,
    -937,  -937,   836,   837,  9585,   844,   189,   847,  -937,  -937,
    -937,  5438,   733,   838,  8991,    31,   166,  -937,  -937,   865,
    -937,  8818,  -937,  8991,   867,   327,  -937,  -937,  -937,  -937,
     263,   840,  -937,  -937,   327,  -937,  -937,  2205,  -937,  -937,
    -937,    57,   952,   953,  1448,  -937,  9307,  -937,  -937,  -937,
    -937,  -937,  -937,  -937,  -937,   896,   848,  6995,    48,   878,
    -937,  -937,   860,   171,  -937,   868,   302,   469,   469,   302,
     975,   855,   975,   856,  -937,  2064,  -937,  1890,  5611,   858,
     862,  -937,  9276,  -937,  -937,  -937,  8991,  -937,  9824,  8991,
     242,  -937,  5784,  -937,  -937, 10239, 11109,   376,  -937,  1012,
     256,  7157,  -937, 10403,  -937,  -937,  -937,  -937,  -937,  1013,
    -937,  -937,  -937,  -937,  -937,  -937,    86,  -937,  -937,  -937,
    -937,  -937,  -937,   870,  -937,  -937,  -937,  -937,  6995,  6995,
    9824,  9824,   327,  -937,   866,  -937,  -937,  1037,  -937, 10598,
    -937,  1041,   482,  -937,  -937, 11109,  1043,  1044,  1045,  1046,
    1047, 11202,   527,  -937,  -937, 10651,  -937,  -937,   874,  -937,
    1027,   882,  -937,   879, 10786,  5957,  -937,  6995,  6995,  -937,
     880,  8991,   881,   898,  -937,  -937, 10735,  -937,   888,   890,
    1005,   988,   906,  8991,   891,  1050,  -937,  -937,  8991,  8991,
    1043,   586, 11202,  -937,  -937,  8991,    27,  -937,  -937,    86,
     893,  -937,  -937,   902,  -937,  9824,  -937,  -937,  -937,  -937,
    -937, 10923,   327, 11109,  9824,  -937,  1076,  -937,   907,  9824,
    9824,  -937,  -937,  9824,  8991,  -937,  -937,  6130,  -937,  -937,
    6303,  -937,  6476,  -937,  -937, 11109,   755,  -937,   904,   500,
    8991,  -937,  1094,  -937,  -937,  -937,  2738,  1171,  -937,  -937,
    -937,  -937,  -937,  -937,  1587,  1622,   256,  -937,  9824,   908,
    -937,  -937,  -937,  -937,  1413,  -937,  1062,  -937,   975,  -937,
    -937,  -937,  -937,   126,   912,  -937,  -937,  -937,  1094,  -937,
    6649,  -937,   913,    50,  -937,  -937,  8991,  -937,  -937,  -937,
    9514,  6822,  -937,  -937,  -937
    };
  }

/* YYDEFACT[STATE-NUM] -- Default reduction number in state STATE-NUM.
   Performed when YYTABLE does not specify something else to do.  Zero
   means the default is an error.  */
  private static final short[] yydefact_ = yydefact_init();
  private static final short[] yydefact_init()
  {
    return new short[]
    {
      87,     0,     2,     1,     0,     0,     0,     0,     0,   536,
     537,    94,    96,    97,    95,   581,   164,   534,     0,     0,
       0,     0,     0,     0,   491,     0,   202,     0,   530,     0,
       0,     0,     0,     0,     0,     0,     0,     0,   558,   558,
       0,   504,   503,     0,   558,     0,     0,     0,     0,   522,
     204,   205,   206,     0,     0,     0,     0,   196,   207,   209,
     211,   119,     0,     0,   545,   546,   547,   553,   548,   549,
     550,   551,   552,   505,     0,     0,     0,     0,     0,     0,
       0,     0,     0,     0,     0,   171,     0,   146,   599,   527,
       0,     0,   544,   103,     0,   110,   113,    86,   112,   105,
     106,     0,   198,   107,   108,   109,   500,   256,   152,     0,
     153,   473,   474,     0,   496,   508,   508,   576,     0,   541,
     488,   542,   543,     0,   566,     0,   520,   577,   415,   572,
     578,   478,    94,   522,     0,     0,   457,   508,   458,   459,
     460,   487,   174,   628,   629,     0,   631,   632,   490,   492,
     494,   522,   206,     0,   523,   202,   203,     0,   200,   412,
     524,   414,   586,   525,     0,   420,   421,     0,   531,   486,
       0,     0,   398,   399,     0,     0,   403,     0,     0,     0,
       0,   559,     0,     0,     0,     0,     0,   144,     0,   146,
     495,    90,    93,    91,   125,   126,    92,   141,     0,     0,
       0,   136,     0,   318,   319,   322,     0,   321,   498,   517,
       0,     0,     0,     0,     0,     0,     0,     0,    84,    89,
       3,     4,     5,     6,     7,     8,     9,    10,    46,    47,
      11,    12,    13,    14,    15,    16,    17,    18,    19,    20,
      21,    22,    23,    24,    25,    26,    27,    28,    29,    49,
      50,    51,    52,    71,    53,    41,    42,    43,    70,    44,
      45,    30,    31,    32,    33,    34,    35,    36,    75,    76,
      77,    78,    79,    80,    81,    37,    38,    39,    40,    61,
      59,    60,    72,    56,    57,    58,    48,    54,    55,    66,
      67,    68,    62,    63,    65,    64,    73,    69,    74,    85,
      88,   117,     0,   599,   599,     0,   202,     0,   599,   565,
     563,   567,   564,   436,   438,   479,   480,   481,   482,   483,
     484,   485,     0,   614,     0,   539,     0,     0,     0,   612,
       0,     0,     0,     0,    82,    83,     0,   604,     0,   602,
     598,   600,   528,     0,   529,     0,     0,     0,   583,   516,
       0,   104,   114,   497,   194,   199,     0,     0,     0,     0,
       0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
       0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
       0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
       0,     0,   165,   509,   505,   505,     0,     0,     0,     0,
     558,     0,     0,     0,     0,     0,     0,     0,     0,     0,
       0,     0,     0,     0,     0,   435,   437,     0,   505,     0,
       0,     0,   413,   201,   409,     0,   411,     0,     0,     0,
     558,    94,     3,     4,     5,     6,     7,    46,   491,    12,
      13,   530,    71,   504,   503,    33,    75,    81,    39,    40,
      48,    54,   545,   546,   547,   553,   548,   549,   550,   551,
     552,     0,   306,     0,   129,   311,     0,     0,   301,   129,
     316,   304,   315,     0,     0,   163,     0,     0,     0,     0,
     404,   408,     0,     0,     0,     0,   158,   159,   173,     0,
       0,   111,   160,     0,     0,     0,   141,     0,     0,   121,
       0,   123,     0,   161,     0,     0,   162,   129,   184,   561,
     635,   129,   633,     0,     0,   218,   505,   220,   213,   116,
       0,    87,     0,     0,   100,   129,    98,     0,     0,     0,
     175,     0,     0,     0,   538,   613,     0,     0,   561,   611,
     540,   610,   472,     0,   151,     0,   148,   145,   147,   599,
     607,   561,     0,   533,   599,   489,   535,     0,   499,     0,
       0,   257,     0,   146,   260,     0,     0,   444,   447,   466,
     468,   448,   449,   450,   451,   453,   454,   441,   443,   442,
     471,   439,   440,   463,   464,   461,   462,   467,   469,   470,
     455,   456,   477,   452,   465,   446,   445,     0,   187,   188,
     505,     0,     0,   554,   584,     0,     0,   555,   585,     0,
     595,     0,   597,   579,   580,     0,   521,     0,   418,   422,
     423,   424,   426,   427,   428,   429,   430,   431,   432,   433,
     434,   425,   630,   493,   526,   530,   590,   588,   589,   591,
       0,   309,   314,     0,   130,     0,     0,   303,   130,     0,
       0,   397,     0,     0,   407,   400,     0,     0,   169,     0,
       0,   505,   143,   176,   142,     0,     0,   122,   124,   141,
     135,   317,   323,   320,   130,     0,   130,     0,   627,   115,
       0,   222,     0,     0,   505,     0,   222,    87,     0,     0,
     532,   130,     0,    99,   562,   533,   616,   617,     0,   622,
     625,   623,     0,   619,     0,   618,   621,     0,   149,     0,
       0,     0,   603,     0,   601,   582,   218,     0,     0,     0,
     476,     0,   268,     0,   268,     0,   518,     0,   519,     0,
     574,   575,   573,   419,   218,   587,   313,     0,   312,   307,
     308,   310,   305,   302,   146,   254,     0,   146,   252,   154,
       0,     0,   402,     0,   405,     0,   599,     0,     0,   561,
       0,   238,   238,   157,   244,   396,   182,   139,     0,   129,
     132,   137,     0,   185,     0,   634,   626,   219,     0,   505,
     325,   221,   335,     0,     0,   279,   290,   291,     0,   292,
     214,   274,     0,   276,   277,   278,   505,     0,   120,     0,
     101,   102,   624,   615,     0,     0,   609,   599,   561,   417,
     222,     0,     0,     0,   475,   368,   369,   370,   364,   363,
     362,   367,   366,   365,   371,   268,     0,   129,   264,   272,
     267,   269,   360,   268,     0,   556,   557,   596,   222,   258,
       0,     0,   403,     0,   406,   599,     0,   561,     0,     0,
     146,   232,   170,   238,     0,   238,     0,   129,     0,   129,
     246,   129,   250,     0,     0,   172,     0,   138,   130,     0,
     129,   134,   166,   223,     0,   356,     0,   325,   275,     0,
       0,     0,     0,     0,     0,     0,   118,   416,     0,   150,
       0,   505,   255,   146,   261,   266,   299,   268,   262,     0,
       0,   190,   273,   286,     0,   288,   289,   361,     0,   510,
     505,   155,     0,     0,   401,     0,   533,     0,   146,   230,
     167,     0,     0,     0,     0,     0,     0,   234,   130,     0,
     243,   130,   245,   130,     0,     0,   146,   140,   131,   128,
     130,     0,   325,   505,     0,   355,   208,   356,   331,   332,
     324,   272,     0,     0,   354,   336,   356,   281,   284,   280,
     282,   283,   285,   325,   620,   608,     0,   259,     0,     0,
     265,   287,     0,     0,   191,   192,     0,     0,     0,     0,
     299,     0,   299,     0,   253,     0,   226,     0,     0,     0,
       0,   236,     0,   146,   146,   235,     0,   247,   251,     0,
     180,   178,     0,   133,   127,   356,     0,     0,   333,     0,
     508,     0,   210,   356,   325,   300,   506,   294,   193,     0,
     297,   293,   295,   296,   298,   506,     0,   506,   325,   146,
     228,   156,   168,     0,   233,   237,   146,   146,   241,   242,
     249,   248,     0,   181,     0,   183,   197,   216,   337,     0,
     334,   505,     0,   373,   327,     0,    94,   279,   290,   291,
       0,     0,     0,   393,   212,   356,   507,   505,     0,   514,
       0,   129,   513,     0,   356,     0,   231,   239,   240,   179,
       0,     0,     0,    75,   338,   349,     0,   340,     0,     0,
       0,   350,     0,     0,   374,     0,   326,   505,     0,     0,
       0,     0,     0,   328,   195,     0,   381,   146,   515,   130,
       0,   146,   410,     0,   146,   217,   215,   339,   341,   342,
     343,     0,     0,     0,   505,   378,   505,   372,     0,   505,
     505,   329,   392,   506,     0,   378,   270,     0,   512,   511,
       0,   229,     0,   345,   346,   348,   344,   351,   375,   383,
       0,   374,   268,   394,   395,   502,   381,   383,   506,   506,
     177,   347,   378,   376,   383,   384,   508,   379,   505,     0,
     271,   382,   189,   501,   383,   380,     0,   375,   299,   377,
     505,   506,   385,     0,   390,   352,   146,   506,   268,   506,
       0,   330,     0,     0,   353,   391,     0,   387,   146,   506,
       0,     0,   386,   389,   388
    };
  }

/* YYPGOTO[NTERM-NUM].  */
  private static final short[] yypgoto_ = yypgoto_init();
  private static final short[] yypgoto_init()
  {
    return new short[]
    {
    -937,  -937,   -31,  -790,  -114,   -58,  -470,  -937,   -40,  -184,
     333,   402,  -937,   -90,    -2,     6,  1000,  -937,  -937,  -937,
    1048,  -937,  -937,  -340,  -937,  -937,   900,   233,  -720,   605,
     928,  -177,  -937,    75,  -937,  -937,  -937,  -937,  -937,  -937,
     433,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,    14,  -937,  -937,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -649,  -937,  -591,   260,  -937,   127,  -937,  -937,  -607,
    -937,  -937,  -937,   182,  -937,  -937,  -937,  -937,  -937,  -937,
    -702,  -937,   218,  -937,   293,   168,  -753,  -418,  -169,  -937,
     332,  -937,  -615,  -208,  -937,   222,  -936,    69,  -937,  -937,
    -937,   959,   -80,  -937,   622,  -937,   620,  -718,   181,  -937,
    -751,  -937,  -937,    40,  -937,  -937,  -937,  -937,  -937,  -937,
    -937,  -937,  -294,  -822,  -937,    35,  -937,  -927,   -27,  -937,
     -32,  -937,  -937,  -937,    73,    46,   653,  -937,   663,  -937,
     294,   484,   996,  -937,   -64,  -937,    11,   -10,  -937,     4,
      45,  -922,  -937,  -111,  -937,  -937,    43,  -937,  -937,   153,
    -230,  -937,   518,   -14,  -937,    -6,    33,     3,  -937,  -937,
    -937,  -937,  -937,   135,   202,  -937,  -937,   757,    41,  -283,
     595,  -937,  -937,   684,   437,  -937,  -937,  -937,   480
    };
  }

/* YYDEFGOTO[NTERM-NUM].  */
  private static final short[] yydefgoto_ = yydefgoto_init();
  private static final short[] yydefgoto_init()
  {
    return new short[]
    {
       0,     1,   298,   299,   336,   463,     2,   301,   767,   197,
      92,   524,   525,    93,   135,   546,    96,    97,   520,   302,
     768,   497,   199,   645,   769,   870,   200,   770,   771,   201,
     186,   331,   547,   548,   760,   766,  1000,  1044,   865,   507,
     508,   600,    99,   975,  1019,   100,   559,   214,   101,   156,
     157,   102,   103,   215,   104,   216,   105,   217,   686,   948,
    1082,   681,   684,   779,   758,  1031,   920,   852,   763,   854,
     106,   858,   859,   860,   861,   749,   107,   108,   109,   110,
     826,   827,   828,   829,   830,   901,   790,   791,   792,   793,
     794,   902,   795,   904,   905,   906,   969,   168,   469,   165,
     464,   470,   471,   202,   203,   206,   207,   875,   949,   950,
     781,  1050,  1086,  1087,  1088,  1089,  1090,  1091,  1187,   951,
     952,   953,   831,   832,  1052,  1053,  1054,  1149,  1136,  1166,
    1167,  1184,  1199,  1189,  1062,  1063,   187,   171,   172,   750,
     479,   480,   159,   635,   111,   112,   113,   114,   115,   137,
     601,  1066,  1105,   394,   982,  1071,  1072,   117,   401,   118,
     161,   343,   169,   119,   120,   121,   122,   182,   123,   124,
     125,   126,   127,   128,   129,   130,   163,   605,   613,   338,
     339,   340,   341,   328,   329,   702,   131,   511,   512
    };
  }

/* YYTABLE[YYPACT[STATE-NUM]] -- What to do in state STATE-NUM.  If
   positive, shift that token.  If negative, reduce the rule whose
   number is the opposite.  If YYTABLE_NINF, syntax error.  */
  private static final short[] yytable_ = yytable_init();
  private static final short[] yytable_init()
  {
    // split by tools/parser/post_bison.py: one initializer of 11318 entries is too large a method
    short[] table = new short[11318];
    System.arraycopy(yytable_init_0(), 0, table, 0, 3000);
    System.arraycopy(yytable_init_1(), 0, table, 3000, 3000);
    System.arraycopy(yytable_init_2(), 0, table, 6000, 3000);
    System.arraycopy(yytable_init_3(), 0, table, 9000, 2318);
    return table;
  }

  private static short[] yytable_init_0()
  {
    return new short[]
    {
     94,   393,   393,   300,   351,   395,   116,   196,    95,   907,
    309,   309,   493,   -14,   496,   136,   138,   139,   140,   141,
    522,   523,   834,   393,   155,   529,   418,   873,   761,   143,
    144,  1134,   146,   147,   148,   149,   150,   993,   166,   208,
    158,   173,   183,    15,  1025,   351,  1027,   188,   867,   181,
    181,   688,   871,   784,  -280,   181,    15,   190,   494,   375,
    310,   310,   899,  -293,   323,   351,   324,   810,   311,   311,
    848,   132,    12,    13,    14,   494,   527,    98,    50,    51,
    132,    12,    13,    14,   353,   838,   152,   315,   316,   317,
    318,   319,   320,   321,   322,   796,   849,   330,   116,   337,
     95,   924,   925,  1068,   323,  1073,   174,   312,   312,   369,
    370,   371,   610,  1069,     3,   355,    15,   323,   305,   342,
    323,   209,   539,   208,  -523,   353,   323,   -84,   345,   649,
    785,   908,   907,   132,    12,    13,    14,    15,   167,   375,
    367,   368,   369,   370,   371,   580,   309,   366,   367,   368,
    369,   370,   371,   786,   787,   856,   419,   323,   196,   956,
    495,   349,   786,   787,   421,   598,   323,   675,   539,   158,
    423,   677,   375,   142,   323,   466,   539,   666,   472,   160,
    375,   473,   388,   -84,   167,   692,   477,   481,   482,   145,
    484,   485,   151,  1007,   167,   347,   310,   325,   326,   327,
     91,   762,  1196,   209,   311,   884,  1135,   994,  1157,   313,
    314,  1155,   957,    91,   903,  1015,   927,   797,   881,   891,
   1003,  1060,   510,   513,  1005,   788,  1197,   976,   162,  1198,
    426,   924,   925,   599,   900,  1174,  1172,  1173,   326,   327,
     41,    42,  1181,   312,  -224,  1013,   922,   910,   926,  1042,
    204,   326,   327,   205,   326,   327,   334,   335,  1061,  1183,
    326,   327,   388,   309,   170,  1191,   709,  1193,   611,  1043,
   -224,  1060,   309,    91,   385,   386,   556,  1202,   175,   367,
    368,   369,   370,   371,   971,   972,   191,   153,   617,   193,
    534,   326,   327,   348,    91,   388,  1065,    41,    42,  1145,
    326,   327,  1185,   388,   155,  1186,   176,   540,   326,   327,
   1074,   375,  1060,   310,   337,   337,   669,   177,   528,   337,
    158,   311,   310,    41,    42,   132,    12,    13,    14,   545,
    311,  -225,  -522,   178,   350,   116,   903,   537,   603,   607,
    558,  -227,   979,   907,   550,   509,   995,   356,   357,  1017,
    132,    12,    13,    14,   358,   359,   360,  -225,   557,   154,
    312,  1020,  1021,  1021,  1024,   179,   878,  -227,    73,   312,
    879,  1146,   566,   567,   568,   569,   570,   571,   572,   573,
    574,   575,   576,   577,   578,   579,   719,   581,   582,   583,
    584,   585,   586,   587,   588,   589,   590,   591,   592,   593,
    594,   595,   596,   615,   180,   385,   386,   786,   787,   151,
    474,   181,   475,   618,   619,   620,   621,   622,   623,   624,
    625,   626,   627,   628,   629,   630,   334,   335,   631,   869,
    184,   633,   561,   640,   388,   185,   208,   155,   711,   597,
    614,   181,   210,   143,   144,   189,   146,   147,   148,   149,
   1169,   166,   211,   158,   191,   351,   190,   193,   526,   160,
    196,   212,   538,   958,   959,   959,   962,   213,   637,   638,
    616,   551,   642,   846,   132,    12,    13,    14,   642,   132,
     12,    13,    14,    15,   490,   173,  1192,   898,   658,   654,
    303,   545,   132,    12,    13,    14,   304,   116,   191,   192,
    661,   193,   194,  -186,   195,  -565,  -565,  -186,   162,   396,
   -563,  -563,    50,    51,  -568,   672,   209,   929,  -565,   932,
    152,   934,   397,  -563,   890,  -606,  -606,  -560,   160,  -606,
    941,   424,  -571,   785,   388,   353,  -565,  -570,   151,    50,
     51,  -563,   490,   757,   491,    73,   194,   152,   195,   116,
    876,   708,  1048,   309,   425,  1049,   786,   787,    41,    42,
    337,   682,   915,   712,   738,   337,   741,   839,   742,   476,
    841,   717,    73,   718,   786,   787,   720,   162,   923,   924,
    925,   954,   815,   816,   817,   818,   819,   820,   821,   822,
    823,   824,   160,   309,   486,   693,   426,   487,   604,   608,
    612,   612,   488,   310,  -567,  -567,   132,    12,    13,    14,
    309,   311,   500,   725,   501,   489,    73,   727,    50,    51,
   -605,  -605,   729,   502,  -605,   503,   152,   636,   788,   612,
    612,   639,   354,   153,   505,  -567,   506,   492,   154,   154,
     91,   162,   500,   310,   668,   723,   900,   309,  -562,  -562,
    312,   311,   499,   954,   736,   472,  1095,   472,  1096,   472,
    310,  -562,   954,   746,   504,   785,   752,   754,   311,   703,
    704,  -564,  -564,   921,   726,  -569,   863,   864,   728,  -562,
   1163,   514,   730,   731,  -564,   515,    94,   510,   786,   787,
    312,   516,   116,   309,    95,   398,   399,   310,   517,   427,
    428,  1102,  -564,  1103,   204,   311,   765,   312,   154,   531,
    532,   954,   429,   960,   961,   804,   967,   545,   518,   954,
    825,   521,   825,   116,   809,   745,   400,   519,   748,   783,
    430,  1110,   814,  -571,   757,   351,   553,  -561,  -561,  -570,
    533,   988,   549,   310,   312,   554,   191,   192,   642,   193,
   -561,   311,   733,   560,   132,    12,    13,    14,    15,  1002,
   1102,   535,  1131,    98,   844,   541,   555,   337,  -561,  1022,
   1023,   954,   154,   344,   346,   862,   562,   698,   563,   535,
    954,   541,   535,   541,   309,   564,   -43,   699,   -70,   -66,
    312,   700,   759,   -67,   -68,    94,   701,   990,   924,   925,
    -62,   116,   -63,    95,   -65,   -64,   -73,   -69,   643,   509,
    887,   644,   648,   151,    50,    51,  1038,  1039,   337,   652,
    655,   656,   152,   674,   874,   665,   664,   667,  -202,   676,
    679,   825,   205,   777,   310,   851,   782,   545,   680,   545,
    683,   885,   311,   116,   526,   116,   808,    73,   685,   687,
    689,   690,  1075,   481,   914,  1165,   337,   351,   695,  1077,
   1078,   691,   696,  1165,   697,   706,   707,   713,   716,   722,
   1165,   724,    98,   947,  -592,  -594,   732,    41,    42,    43,
   1165,   312,   735,   739,   743,   494,   892,   764,   350,    50,
     51,   772,   847,   774,   776,   825,   393,   152,   780,  1055,
    799,   801,  -569,    57,    58,    59,    60,   802,   153,   778,
    803,   807,  1070,   866,   805,    91,   806,   833,   842,   545,
    843,   845,    73,   853,   919,   116,   855,   868,   872,   876,
   1137,   782,   882,   877,  1140,   992,   966,  1142,   883,   884,
     41,    42,   862,   889,   998,   893,   894,   896,  1047,   897,
   -593,   350,    50,    51,   947,   983,  1010,   909,   930,   916,
    152,   911,   881,   928,   931,   545,    57,    58,    59,    60,
    935,   116,   937,  -607,  -607,   366,   367,   368,   369,   370,
    371,   968,   933,   759,   939,    73,   545,   940,  1006,   936,
    942,  1085,   116,   963,   974,  1070,   964,  1097,   965,   977,
    545,   978,   979,   947,   980,   981,   116,  1040,   375,  1190,
   1041,   947,   984,   154,   991,   985,   154,   996,   789,   999,
   1004,  1201,   986,    41,   154,   987,  1011,  1014,  1085,   955,
   1016,   976,  1026,  1018,  1034,  1028,   545,   545,  1035,  1051,
   1067,  1081,   116,   116,  1080,  1093,  1076,  1098,   -75,   -54,
    -55,  1099,   393,  1107,  1108,  1176,  1109,  1116,  1111,  1114,
   1030,  -522,   919,   947,  1119,  1147,  1120,  1121,  1122,  1123,
   1125,  1139,   947,   545,   351,   545,   545,  1126,  1141,   116,
   1150,   116,   116,  1162,  1152,  1180,  1178,  1161,  1001,  1188,
   1144,  1195,  1115,   800,   352,   198,  1094,   782,   498,  -607,
   -607,   938,   385,   386,  1124,   670,   483,   773,   917,  1129,
   1130,   154,  1106,   997,  1032,   970,  1133,   789,   895,  1009,
    880,   789,   973,   465,   671,   673,  1118,   402,  1008,  1170,
   1127,   388,  1175,   389,  1101,   545,   913,   651,   545,   751,
    545,   116,  1128,   662,   116,  1156,   116,  1164,  1132,   714,
    825,   422,  1138,   734,   609,  1164,   775,     0,     0,     0,
      0,  1168,   789,     0,     0,     0,     0,     0,     0,  1148,
      0,  1151,  1164,     0,  1153,  1154,   815,   816,   817,   818,
    819,   820,   821,   822,   823,   824,   825,     0,   545,     0,
      0,     0,     0,     0,   116,  1079,     0,     0,     0,   545,
      0,     0,  1092,     0,     0,   116,     0,  1200,     0,   154,
     73,     0,     0,  1177,   789,   789,   789,   789,     0,     0,
      0,     0,     0,     0,     0,  1182,     0,     0,     0,     0,
      0,     0,   789,   789,     0,     0,     0,     0,     0,  1092,
    403,   404,   405,   406,   407,   408,   409,   410,   411,   412,
    413,   414,     0,   815,   816,   817,   818,   819,   820,   821,
    822,   823,   824,   415,   416,     0,     0,     0,   154,     0,
      0,     0,  -263,  -561,  -561,   782,     0,   154,     0,     0,
      0,     0,     0,     0,   789,     0,  -561,    73,     0,     0,
      0,   417,     0,     0,     0,     0,     0,     0,     0,     0,
      0,   789,     0,     0,  -561,     0,     0,     0,   943,   789,
    789,   789,   789,     0,     0,     0,  -358,     0,     0,     0,
      0,     0,   944,     0,     0,   815,   816,   817,   818,   819,
    820,   821,   822,   823,   824,   945,     0,     0,     0,     0,
      0,     0,     0,     0,   789,     0,     0,     0,     0,     0,
      0,  1171,     0,     0,     0,     0,     0,     0,     0,    73,
      0,     0,   362,   363,   364,   365,   366,   367,   368,   369,
    370,   371,     0,     0,   744,   154,     0,     0,     0,     0,
      4,     5,   154,     0,     0,     6,     7,     8,     0,     9,
     10,    11,    12,    13,    14,    15,    16,     0,    17,   375,
      0,    18,    19,    20,    21,    22,     0,     0,     0,    23,
     24,    25,     0,    26,    27,    28,    29,     0,     0,   154,
     30,    31,    32,   946,    33,     0,    34,     0,    35,     0,
      0,    36,     0,     0,     0,    37,    38,    39,    40,    41,
     42,     0,    44,    45,     0,     0,    46,     0,     0,    48,
     49,     0,     0,     0,     0,   154,     0,     0,     0,   134,
      0,    53,    54,    55,     0,     0,     0,     0,     0,     0,
      0,     0,    62,    63,     0,    64,    65,    66,    67,    68,
     69,    70,    71,    72,    73,   377,   378,   379,   380,   381,
    382,   383,   384,   385,   386,   815,   816,   817,   818,   819,
    820,   821,   822,   823,   824,     0,     0,     0,    74,    75,
     76,    77,    78,    79,    80,    81,    82,    83,     0,  -357,
      0,  -359,   388,     0,   389,   390,   391,    84,     0,    73,
    815,   816,   817,   818,   819,   820,   821,   822,   823,   824,
      0,     0,     0,     0,    85,    86,     0,    87,   747,    88,
     89,    90,    91,     0,     4,     5,     0,     0,     0,     6,
      7,     8,     0,     9,    10,    11,    12,    13,    14,    15,
     16,     0,    17,     0,     0,    18,    19,    20,    21,    22,
      0,     0,     0,    23,    24,    25,     0,    26,    27,    28,
     29,     0,     0,  1179,    30,    31,    32,     0,    33,     0,
     34,     0,    35,     0,     0,    36,     0,     0,     0,    37,
     38,    39,    40,    41,    42,     0,    44,    45,     0,     0,
     46,     0,     0,    48,    49,     0,     0,     0,     0,     0,
      0,     0,     0,   134,     0,    53,    54,    55,     0,     0,
      0,     0,     0,     0,     0,     0,    62,    63,     0,    64,
     65,    66,    67,    68,    69,    70,    71,    72,    73,   815,
    816,   817,   818,   819,   820,   821,   822,   823,   824,   815,
    816,   817,   818,   819,   820,   821,   822,   823,   824,     0,
      0,     0,    74,    75,    76,    77,    78,    79,    80,    81,
     82,    83,     0,    73,     0,     0,     0,     0,     0,     0,
      0,    84,     0,    73,   815,   816,   817,   818,   819,   820,
    821,   822,   823,   824,     0,     0,     0,     0,    85,    86,
      0,    87,   850,    88,    89,    90,    91,     0,     4,     5,
      0,     0,     0,     6,     7,     8,     0,     9,    10,    11,
     12,    13,    14,    15,    16,     0,    17,     0,     0,    18,
     19,    20,    21,    22,     0,  -130,     0,    23,    24,    25,
      0,    26,    27,    28,    29,     0,     0,     0,    30,    31,
     32,     0,    33,     0,    34,     0,    35,     0,     0,    36,
      0,     0,     0,    37,    38,    39,    40,    41,    42,     0,
     44,    45,     0,     0,    46,     0,     0,    48,    49,     0,
      0,     0,     0,     0,     0,     0,     0,   134,     0,    53,
     54,    55,     0,     0,     0,     0,     0,     0,     0,     0,
     62,    63,     0,    64,    65,    66,    67,    68,    69,    70,
     71,    72,    73,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    74,    75,    76,    77,
     78,    79,    80,    81,    82,    83,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    84,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    85,    86,     0,    87,   918,    88,    89,    90,
     91,     0,     4,     5,     0,     0,     0,     6,     7,     8,
      0,     9,    10,    11,    12,    13,    14,    15,    16,     0,
     17,     0,     0,    18,    19,    20,    21,    22,     0,     0,
      0,    23,    24,    25,     0,    26,    27,    28,    29,     0,
      0,     0,    30,    31,    32,     0,    33,     0,    34,     0,
     35,     0,     0,    36,     0,     0,     0,    37,    38,    39,
     40,    41,    42,     0,    44,    45,     0,     0,    46,     0,
      0,    48,    49,     0,     0,     0,     0,     0,     0,     0,
      0,   134,     0,    53,    54,    55,     0,     0,     0,     0,
      0,     0,     0,     0,    62,    63,     0,    64,    65,    66,
     67,    68,    69,    70,    71,    72,    73,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     74,    75,    76,    77,    78,    79,    80,    81,    82,    83,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    84,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    85,    86,     0,    87,
   1029,    88,    89,    90,    91,     0,     4,     5,     0,     0,
      0,     6,     7,     8,     0,     9,    10,    11,    12,    13,
     14,    15,    16,     0,    17,     0,     0,    18,    19,    20,
     21,    22,     0,     0,     0,    23,    24,    25,     0,    26,
     27,    28,    29,     0,     0,     0,    30,    31,    32,     0,
     33,     0,    34,     0,    35,     0,     0,    36,     0,     0,
      0,    37,    38,    39,    40,    41,    42,     0,    44,    45,
      0,     0,    46,     0,     0,    48,    49,     0,     0,     0,
      0,     0,     0,     0,     0,   134,     0,    53,    54,    55,
      0,     0,     0,     0,     0,     0,     0,     0,    62,    63,
      0,    64,    65,    66,    67,    68,    69,    70,    71,    72,
     73,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    74,    75,    76,    77,    78,    79,
     80,    81,    82,    83,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    84,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     85,    86,     0,    87,   565,    88,    89,    90,    91,     0,
      4,     5,     0,     0,     0,     6,     7,     8,     0,     9,
     10,   132,    12,    13,    14,    15,     0,     0,    17,     0,
    943,    18,    19,    20,    21,    22,     0,     0,  -358,    23,
     24,    25,     0,    26,    27,    28,     0,   815,   816,   817,
    818,   819,   820,   821,   822,   823,   824,   945,     0,     0,
      0,     0,     0,     0,     0,    37,     0,     0,     0,    41,
     42,     0,     0,     0,     0,     0,    46,     0,     0,     0,
    133,    73,     0,     0,     0,     0,     0,     0,     0,   134,
      0,     0,    54,    55,     0,     0,     0,     0,     0,     0,
      0,     0,    62,    63,     0,    64,    65,    66,    67,    68,
     69,    70,    71,    72,    73,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    74,    75,
     76,    77,    78,    79,    80,    81,    82,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    84,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    86,     0,   -47,     0,    88,
     89,    90,    91,     4,     5,     0,     0,     0,     6,     7,
      8,     0,     9,    10,   132,    12,    13,    14,    15,     0,
      0,    17,     0,     0,    18,    19,    20,    21,    22,     0,
      0,     0,    23,    24,    25,     0,    26,    27,    28,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    37,     0,
      0,     0,    41,    42,     0,     0,     0,     0,     0,    46,
      0,     0,     0,   133,     0,     0,     0,     0,     0,     0,
      0,     0,   134,     0,     0,    54,    55,     0,     0,     0,
      0,     0,     0,     0,     0,    62,    63,     0,    64,    65,
     66,    67,    68,    69,    70,    71,    72,    73,   361,     0,
    362,   363,   364,   365,   366,   367,   368,   369,   370,   371,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    74,    75,    76,    77,    78,    79,    80,    81,    82,
      0,   372,   373,   374,     0,     0,     0,   375,     0,     0,
     84,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    86,     0,
      0,     0,    88,    89,    90,    91,   361,     0,   362,   363,
    364,   365,   366,   367,   368,   369,   370,   371,   361,     0,
    362,   363,   364,   365,   366,   367,   368,   369,   370,   371,
      0,     0,     0,     0,     0,     0,     0,     0,     0,   372,
    373,   374,     0,     0,     0,   375,     0,     0,     0,     0,
      0,   372,   373,   374,     0,     0,     0,   375,     0,     0,
      0,     0,   376,   377,   378,   379,   380,   381,   382,   383,
    384,   385,   386,   361,     0,   362,   363,   364,   365,   366,
    367,   368,   369,   370,   371,   361,     0,   362,   363,   364,
    365,   366,   367,   368,   369,   370,   371,     0,     0,   387,
    388,     0,   389,   390,   391,     0,   372,   373,   374,     0,
      0,     0,   375,   705,     0,     0,     0,     0,   372,   373,
    374,     0,     0,     0,   375,     0,     0,     0,     0,     0,
    376,   377,   378,   379,   380,   381,   382,   383,   384,   385,
    386,     0,   376,   377,   378,   379,   380,   381,   382,   383,
    384,   385,   386,   361,     0,   362,   363,   364,   365,   366,
    367,   368,   369,   370,   371,     0,     0,   387,   388,     0,
    389,   390,   391,     0,     0,     0,     0,     0,     0,   387,
    388,   715,   389,   390,   391,     0,   372,   373,   374,     0,
      0,     0,   375,   835,     0,     0,     0,   376,   377,   378,
    379,   380,   381,   382,   383,   384,   385,   386,     0,   376,
    377,   378,   379,   380,   381,   382,   383,   384,   385,   386,
    361,     0,   362,   363,   364,   365,   366,   367,   368,   369,
    370,   371,     0,     0,   387,   388,     0,   389,   390,   391,
      0,     0,     0,     0,     0,     0,   387,   388,   836,   389,
    390,   391,     0,   372,   373,   374,     0,     0,     0,   375,
    837,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,   376,   377,   378,
    379,   380,   381,   382,   383,   384,   385,   386,     0,   361,
      0,   362,   363,   364,   365,   366,   367,   368,   369,   370,
    371,     0,   361,     0,   362,   363,   364,   365,   366,   367,
    368,   369,   370,   371,   387,   388,     0,   389,   390,   391,
      0,     0,   372,   373,   374,     0,     0,  1135,   375,     0,
      0,     0,     0,     0,     0,   372,   373,   374,     0,     0,
      0,   375,     0,     0,   376,   377,   378,   379,   380,   381,
    382,   383,   384,   385,   386,     0,     0,   361,     0,   362,
    363,   364,   365,   366,   367,   368,   369,   370,   371,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,   387,   388,     0,   389,   390,   391,     0,     0,   646,
    372,   373,   374,   542,     0,     0,   375,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0
    };
  }

  private static short[] yytable_init_1()
  {
    return new short[]
    {
      0,     0,     0,   376,   377,   378,   379,   380,   381,   382,
    383,   384,   385,   386,     0,     0,   376,   377,   378,   379,
    380,   381,   382,   383,   384,   385,   386,   361,     0,   362,
    363,   364,   365,   366,   367,   368,   369,   370,   371,     0,
    387,   388,     0,   389,   390,   391,     0,     0,     0,     0,
      0,     0,   542,   387,   388,     0,   389,   390,   391,     0,
    372,   373,   374,     0,     0,   632,   375,     0,     0,     0,
      0,   376,   377,   378,   379,   380,   381,   382,   383,   384,
    385,   386,   361,     0,   362,   363,   364,   365,   366,   367,
    368,   369,   370,   371,     0,   361,     0,   362,   363,   364,
    365,   366,   367,   368,   369,   370,   371,     0,   387,   388,
      0,   389,   390,   391,     0,   372,   373,   374,     0,     0,
    634,   375,     0,     0,     0,     0,     0,     0,   372,   373,
    374,     0,     0,     0,   375,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,   376,   377,   378,   379,   380,   381,   382,   383,   384,
    385,   386,   361,     0,   362,   363,   364,   365,   366,   367,
    368,   369,   370,   371,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,   387,   388,
      0,   389,   390,   391,     0,   372,   373,   374,     0,     0,
    650,   375,     0,     0,     0,     0,   376,   377,   378,   379,
    380,   381,   382,   383,   384,   385,   386,     0,     0,   376,
    377,   378,   379,   380,   381,   382,   383,   384,   385,   386,
    361,     0,   362,   363,   364,   365,   366,   367,   368,   369,
    370,   371,     0,   387,   388,     0,   389,   390,   391,     0,
      0,     0,     0,     0,     0,   653,   387,   388,     0,   389,
    390,   391,     0,   372,   373,   374,     0,     0,   659,   375,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,   376,   377,   378,   379,
    380,   381,   382,   383,   384,   385,   386,   361,     0,   362,
    363,   364,   365,   366,   367,   368,   369,   370,   371,     0,
    361,     0,   362,   363,   364,   365,   366,   367,   368,   369,
    370,   371,     0,   387,   388,     0,   389,   390,   391,     0,
    372,   373,   374,     0,     0,   660,   375,     0,     0,     0,
      0,     0,     0,   372,   373,   374,     0,     0,     0,   375,
      0,     0,     0,     0,   376,   377,   378,   379,   380,   381,
    382,   383,   384,   385,   386,   361,     0,   362,   363,   364,
    365,   366,   367,   368,   369,   370,   371,     0,   361,     0,
    362,   363,   364,   365,   366,   367,   368,   369,   370,   371,
      0,   387,   388,     0,   389,   390,   391,     0,   372,   373,
    374,     0,     0,   678,   375,     0,     0,     0,     0,     0,
      0,   372,   373,   374,     0,     0,     0,   375,     0,     0,
      0,   376,   377,   378,   379,   380,   381,   382,   383,   384,
    385,   386,     0,     0,   376,   377,   378,   379,   380,   381,
    382,   383,   384,   385,   386,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,   387,   388,
      0,   389,   390,   391,     0,     0,     0,     0,     0,     0,
    694,   387,   388,     0,   389,   390,   391,     0,     0,     0,
      0,     0,     0,   811,     0,     0,     0,     0,     0,   376,
    377,   378,   379,   380,   381,   382,   383,   384,   385,   386,
      0,     0,   376,   377,   378,   379,   380,   381,   382,   383,
    384,   385,   386,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,   387,   388,     0,   389,
    390,   391,     0,     0,     0,     0,     0,     0,   812,   387,
    388,     0,   389,   390,   391,     0,     0,     4,     5,     0,
      0,   840,     6,     7,     8,     0,     9,    10,   431,    12,
     13,    14,    15,     0,     0,    17,     0,     0,   432,   433,
    434,   435,   436,   225,   226,   227,   437,   438,    25,   230,
    439,   440,   441,   234,   235,   236,   237,   238,   239,   240,
    241,   242,   243,   244,   245,   246,   247,   248,   249,   250,
    251,   252,   442,   254,   255,   256,   443,   444,   259,   260,
    261,   262,   263,   445,   265,   266,   267,   446,   269,   270,
    271,   272,   273,     0,     0,     0,   447,   275,   276,   448,
    449,     0,   279,   280,   281,   282,   283,   284,   285,   450,
    451,   288,   452,   453,   454,   455,   456,   457,   458,   459,
    460,    73,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    74,    75,    76,    77,    78,
     79,    80,    81,    82,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    84,     0,     0,     0,     0,     0,
    461,     0,   362,   363,   364,   365,   366,   367,   368,   369,
    370,   371,    86,   462,     0,     0,    88,    89,    90,    91,
      4,     5,     0,     0,     0,     6,     7,     8,     0,     9,
     10,   431,    12,    13,    14,    15,     0,     0,    17,   375,
      0,   432,   433,   434,   435,   436,   225,   226,   227,   437,
    438,    25,   230,   439,   440,   441,   234,   235,   236,   237,
    238,   239,   240,   241,   242,   243,   244,   245,   246,   247,
    248,   249,   250,   251,   252,   442,   254,   255,   256,   443,
    444,   259,   260,   261,   262,   263,   445,   265,   266,   267,
    446,   269,   270,   271,   272,   273,     0,     0,     0,   447,
    275,   276,   448,   449,     0,   279,   280,   281,   282,   283,
    284,   285,   450,   451,   288,   452,   453,   454,   455,   456,
    457,   458,   459,   460,    73,     0,   378,   379,   380,   381,
    382,   383,   384,   385,   386,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    74,    75,
     76,    77,    78,    79,    80,    81,    82,     0,     0,     0,
      0,     0,   388,     0,   389,   390,   391,    84,     0,     0,
      0,     0,     0,   467,     0,     0,   363,   364,   365,   366,
    367,   368,   369,   370,   371,    86,   468,     0,     0,    88,
     89,    90,    91,     4,     5,     0,     0,     0,     6,     7,
      8,     0,     9,    10,   431,    12,    13,    14,    15,     0,
      0,    17,   375,     0,   432,   433,   434,   435,   436,   225,
    226,   227,   437,   438,    25,   230,   439,   440,   441,   234,
    235,   236,   237,   238,   239,   240,   241,   242,   243,   244,
    245,   246,   247,   248,   249,   250,   251,   252,   442,   254,
    255,   256,   443,   444,   259,   260,   261,   262,   263,   445,
    265,   266,   267,   446,   269,   270,   271,   272,   273,     0,
      0,     0,   447,   275,   276,   448,   449,     0,   279,   280,
    281,   282,   283,   284,   285,   450,   451,   288,   452,   453,
    454,   455,   456,   457,   458,   459,   460,    73,     0,   378,
    379,   380,   381,   382,   383,   384,   385,   386,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    74,    75,    76,    77,    78,    79,    80,    81,    82,
      0,     0,     0,     0,     0,   388,     0,   389,   390,   391,
     84,     0,     0,     0,     0,     0,   737,     0,     0,     0,
    364,   365,   366,   367,   368,   369,   370,   371,    86,   740,
      0,     0,    88,    89,    90,    91,     4,     5,     0,     0,
      0,     6,     7,     8,     0,     9,    10,   431,    12,    13,
     14,    15,     0,     0,    17,   375,     0,   432,   433,   434,
    435,   436,   225,   226,   227,   437,   438,    25,   230,   439,
    440,   441,   234,   235,   236,   237,   238,   239,   240,   241,
    242,   243,   244,   245,   246,   247,   248,   249,   250,   251,
    252,   442,   254,   255,   256,   443,   444,   259,   260,   261,
    262,   263,   445,   265,   266,   267,   446,   269,   270,   271,
    272,   273,     0,     0,     0,   447,   275,   276,   448,   449,
      0,   279,   280,   281,   282,   283,   284,   285,   450,   451,
    288,   452,   453,   454,   455,   456,   457,   458,   459,   460,
     73,     0,   378,   379,   380,   381,   382,   383,   384,   385,
    386,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    74,    75,    76,    77,    78,    79,
     80,    81,    82,     0,     0,     0,     0,     0,   388,     0,
    389,   390,   391,    84,     0,     0,     0,     0,     0,   737,
      0,     0,     0,   364,   365,   366,   367,   368,   369,   370,
    371,    86,     0,     0,     0,    88,    89,    90,    91,     4,
      5,     0,     0,     0,     6,     7,     8,     0,     9,    10,
     11,    12,    13,    14,    15,    16,     0,    17,   375,     0,
     18,    19,    20,    21,    22,     0,     0,     0,    23,    24,
     25,     0,    26,    27,    28,    29,     0,     0,     0,    30,
     31,    32,     0,    33,     0,    34,     0,    35,     0,     0,
     36,     0,     0,     0,    37,    38,    39,    40,    41,    42,
     43,    44,    45,     0,     0,    46,    47,     0,    48,    49,
     50,    51,     0,     0,     0,     0,     0,     0,    52,     0,
     53,    54,    55,    56,    57,    58,    59,    60,     0,     0,
     61,    62,    63,     0,    64,    65,    66,    67,    68,    69,
     70,    71,    72,    73,     0,  -607,  -607,  -607,  -607,   382,
    383,  -607,   385,   386,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    74,    75,    76,
     77,    78,    79,    80,    81,    82,    83,     0,     0,     0,
      0,   388,     0,   389,     0,     0,    84,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    85,    86,     0,    87,   798,    88,    89,
     90,    91,     4,     5,     0,     0,     0,     6,     7,     8,
      0,     9,    10,    11,    12,    13,    14,    15,    16,     0,
     17,     0,     0,    18,    19,    20,    21,    22,     0,     0,
      0,    23,    24,    25,     0,    26,    27,    28,    29,     0,
      0,     0,    30,    31,    32,     0,    33,     0,    34,     0,
     35,     0,     0,    36,     0,     0,     0,    37,    38,    39,
     40,    41,    42,    43,    44,    45,     0,     0,    46,    47,
      0,    48,    49,    50,    51,     0,     0,     0,     0,     0,
      0,    52,     0,    53,    54,    55,    56,    57,    58,    59,
     60,     0,     0,    61,    62,    63,     0,    64,    65,    66,
     67,    68,    69,    70,    71,    72,    73,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     74,    75,    76,    77,    78,    79,    80,    81,    82,    83,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    84,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    85,    86,     0,    87,
    886,    88,    89,    90,    91,     4,     5,     0,     0,     0,
      6,     7,     8,     0,     9,    10,    11,    12,    13,    14,
     15,    16,     0,    17,     0,     0,    18,    19,    20,    21,
     22,     0,     0,     0,    23,    24,    25,     0,    26,    27,
     28,    29,     0,     0,     0,    30,    31,    32,     0,    33,
      0,    34,     0,    35,     0,     0,    36,     0,     0,     0,
     37,    38,    39,    40,    41,    42,    43,    44,    45,     0,
      0,    46,    47,     0,    48,    49,    50,    51,     0,     0,
      0,     0,     0,     0,    52,     0,    53,    54,    55,    56,
     57,    58,    59,    60,     0,     0,    61,    62,    63,     0,
     64,    65,    66,    67,    68,    69,    70,    71,    72,    73,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    74,    75,    76,    77,    78,    79,    80,
     81,    82,    83,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    84,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    85,
     86,     0,    87,     0,    88,    89,    90,    91,     4,     5,
      0,     0,     0,     6,     7,     8,     0,     9,    10,    11,
     12,    13,    14,    15,    16,     0,    17,     0,     0,    18,
     19,    20,    21,    22,     0,     0,     0,    23,    24,    25,
      0,    26,    27,    28,    29,     0,     0,     0,    30,    31,
     32,     0,    33,     0,    34,     0,    35,     0,     0,    36,
      0,     0,     0,    37,    38,    39,    40,    41,    42,     0,
     44,    45,     0,     0,    46,     0,     0,    48,    49,    50,
     51,     0,     0,     0,     0,     0,     0,    52,     0,    53,
     54,    55,   543,    57,    58,    59,    60,     0,     0,     0,
     62,    63,     0,    64,    65,    66,    67,    68,    69,    70,
     71,    72,    73,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    74,    75,    76,    77,
     78,    79,    80,    81,    82,    83,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    84,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    85,    86,     0,    87,   544,    88,    89,    90,
     91,     4,     5,     0,     0,     0,     6,     7,     8,     0,
      9,    10,    11,    12,    13,    14,    15,    16,     0,    17,
      0,     0,    18,    19,    20,    21,    22,     0,     0,     0,
     23,    24,    25,     0,    26,    27,    28,    29,     0,     0,
      0,    30,    31,    32,     0,    33,     0,    34,     0,    35,
      0,     0,    36,     0,     0,     0,    37,    38,    39,    40,
     41,    42,     0,    44,    45,     0,     0,    46,     0,     0,
     48,    49,    50,    51,     0,     0,     0,     0,     0,     0,
     52,     0,    53,    54,    55,   543,    57,    58,    59,    60,
      0,     0,     0,    62,    63,     0,    64,    65,    66,    67,
     68,    69,    70,    71,    72,    73,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    74,
     75,    76,    77,    78,    79,    80,    81,    82,    83,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    84,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    85,    86,     0,    87,   663,
     88,    89,    90,    91,     4,     5,     0,     0,     0,     6,
      7,     8,     0,     9,    10,    11,    12,    13,    14,    15,
     16,     0,    17,     0,     0,    18,    19,    20,    21,    22,
      0,     0,     0,    23,    24,    25,     0,    26,    27,    28,
     29,     0,     0,   813,    30,    31,    32,     0,    33,     0,
     34,     0,    35,     0,     0,    36,     0,     0,     0,    37,
     38,    39,    40,    41,    42,     0,    44,    45,     0,     0,
     46,     0,     0,    48,    49,    50,    51,     0,     0,     0,
      0,     0,     0,    52,     0,    53,    54,    55,   543,    57,
     58,    59,    60,     0,     0,     0,    62,    63,     0,    64,
     65,    66,    67,    68,    69,    70,    71,    72,    73,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    74,    75,    76,    77,    78,    79,    80,    81,
     82,    83,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    84,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    85,    86,
      0,    87,     0,    88,    89,    90,    91,     4,     5,     0,
      0,     0,     6,     7,     8,     0,     9,    10,    11,    12,
     13,    14,    15,    16,     0,    17,     0,     0,    18,    19,
     20,    21,    22,     0,     0,     0,    23,    24,    25,     0,
     26,    27,    28,    29,     0,     0,     0,    30,    31,    32,
    912,    33,     0,    34,     0,    35,     0,     0,    36,     0,
      0,     0,    37,    38,    39,    40,    41,    42,     0,    44,
     45,     0,     0,    46,     0,     0,    48,    49,    50,    51,
      0,     0,     0,     0,     0,     0,    52,     0,    53,    54,
     55,   543,    57,    58,    59,    60,     0,     0,     0,    62,
     63,     0,    64,    65,    66,    67,    68,    69,    70,    71,
     72,    73,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    74,    75,    76,    77,    78,
     79,    80,    81,    82,    83,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    84,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    85,    86,     0,    87,     0,    88,    89,    90,    91,
      4,     5,     0,     0,     0,     6,     7,     8,     0,     9,
     10,    11,    12,    13,    14,    15,    16,     0,    17,     0,
      0,    18,    19,    20,    21,    22,     0,     0,     0,    23,
     24,    25,     0,    26,    27,    28,    29,     0,     0,     0,
     30,    31,    32,     0,    33,     0,    34,     0,    35,   989,
      0,    36,     0,     0,     0,    37,    38,    39,    40,    41,
     42,     0,    44,    45,     0,     0,    46,     0,     0,    48,
     49,    50,    51,     0,     0,     0,     0,     0,     0,    52,
      0,    53,    54,    55,   543,    57,    58,    59,    60,     0,
      0,     0,    62,    63,     0,    64,    65,    66,    67,    68,
     69,    70,    71,    72,    73,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    74,    75,
     76,    77,    78,    79,    80,    81,    82,    83,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    84,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    85,    86,     0,    87,     0,    88,
     89,    90,    91,     4,     5,     0,     0,     0,     6,     7,
      8,     0,     9,    10,    11,    12,    13,    14,    15,    16,
      0,    17,     0,     0,    18,    19,    20,    21,    22,     0,
      0,     0,    23,    24,    25,     0,    26,    27,    28,    29,
      0,     0,     0,    30,    31,    32,     0,    33,     0,    34,
   1033,    35,     0,     0,    36,     0,     0,     0,    37,    38,
     39,    40,    41,    42,     0,    44,    45,     0,     0,    46,
      0,     0,    48,    49,    50,    51,     0,     0,     0,     0,
      0,     0,    52,     0,    53,    54,    55,   543,    57,    58,
     59,    60,     0,     0,     0,    62,    63,     0,    64,    65,
     66,    67,    68,    69,    70,    71,    72,    73,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    74,    75,    76,    77,    78,    79,    80,    81,    82,
     83,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     84,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    85,    86,     0,
     87,     0,    88,    89,    90,    91,     4,     5,     0,     0,
      0,     6,     7,     8,     0,     9,    10,    11,    12,    13,
     14,    15,    16,     0,    17,     0,     0,    18,    19,    20,
     21,    22,     0,     0,     0,    23,    24,    25,     0,    26,
     27,    28,    29,     0,     0,     0,    30,    31,    32,     0,
     33,     0,    34,     0,    35,     0,     0,    36,     0,     0,
      0,    37,    38,    39,    40,    41,    42,     0,    44,    45,
      0,     0,    46,     0,     0,    48,    49,    50,    51,     0,
      0,     0,     0,     0,     0,    52,     0,    53,    54,    55,
    543,    57,    58,    59,    60,     0,     0,     0,    62,    63,
      0,    64,    65,    66,    67,    68,    69,    70,    71,    72,
     73,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    74,    75,    76,    77,    78,    79,
     80,    81,    82,    83,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    84,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     85,    86,     0,    87,  1045,    88,    89,    90,    91,     4,
      5,     0,     0,     0,     6,     7,     8,     0,     9,    10,
     11,    12,    13,    14,    15,    16,     0,    17,     0,     0,
     18,    19,    20,    21,    22,     0,     0,     0,    23,    24
    };
  }

  private static short[] yytable_init_2()
  {
    return new short[]
    {
     25,     0,    26,    27,    28,    29,     0,     0,     0,    30,
     31,    32,     0,    33,  1113,    34,     0,    35,     0,     0,
     36,     0,     0,     0,    37,    38,    39,    40,    41,    42,
      0,    44,    45,     0,     0,    46,     0,     0,    48,    49,
     50,    51,     0,     0,     0,     0,     0,     0,    52,     0,
     53,    54,    55,   543,    57,    58,    59,    60,     0,     0,
      0,    62,    63,     0,    64,    65,    66,    67,    68,    69,
     70,    71,    72,    73,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    74,    75,    76,
     77,    78,    79,    80,    81,    82,    83,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    84,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    85,    86,     0,    87,     0,    88,    89,
     90,    91,     4,     5,     0,     0,     0,     6,     7,     8,
      0,     9,    10,    11,    12,    13,    14,    15,    16,     0,
     17,     0,     0,    18,    19,    20,    21,    22,     0,     0,
      0,    23,    24,    25,     0,    26,    27,    28,    29,     0,
      0,     0,    30,    31,    32,     0,    33,     0,    34,     0,
     35,     0,     0,    36,     0,     0,     0,    37,    38,    39,
     40,    41,    42,     0,    44,    45,     0,     0,    46,     0,
      0,    48,    49,    50,    51,     0,     0,     0,     0,     0,
      0,    52,     0,    53,    54,    55,   543,    57,    58,    59,
     60,     0,     0,     0,    62,    63,     0,    64,    65,    66,
     67,    68,    69,    70,    71,    72,    73,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     74,    75,    76,    77,    78,    79,    80,    81,    82,    83,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    84,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    85,    86,     0,    87,
   1158,    88,    89,    90,    91,     4,     5,     0,     0,     0,
      6,     7,     8,     0,     9,    10,    11,    12,    13,    14,
     15,    16,     0,    17,     0,     0,    18,    19,    20,    21,
     22,     0,     0,     0,    23,    24,    25,     0,    26,    27,
     28,    29,     0,     0,     0,    30,    31,    32,     0,    33,
      0,    34,     0,    35,     0,     0,    36,     0,     0,     0,
     37,    38,    39,    40,    41,    42,     0,    44,    45,     0,
      0,    46,     0,     0,    48,    49,    50,    51,     0,     0,
      0,     0,     0,     0,    52,     0,    53,    54,    55,   543,
     57,    58,    59,    60,     0,     0,     0,    62,    63,     0,
     64,    65,    66,    67,    68,    69,    70,    71,    72,    73,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    74,    75,    76,    77,    78,    79,    80,
     81,    82,    83,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    84,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    85,
     86,     0,    87,  1159,    88,    89,    90,    91,     4,     5,
      0,     0,     0,     6,     7,     8,     0,     9,    10,    11,
     12,    13,    14,    15,    16,     0,    17,     0,     0,    18,
     19,    20,    21,    22,     0,     0,     0,    23,    24,    25,
      0,    26,    27,    28,    29,     0,     0,     0,    30,    31,
     32,     0,    33,     0,    34,     0,    35,     0,     0,    36,
      0,     0,     0,    37,    38,    39,    40,    41,    42,     0,
     44,    45,     0,     0,    46,     0,     0,    48,    49,    50,
     51,     0,     0,     0,     0,     0,     0,    52,     0,    53,
     54,    55,   543,    57,    58,    59,    60,     0,     0,     0,
     62,    63,     0,    64,    65,    66,    67,    68,    69,    70,
     71,    72,    73,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    74,    75,    76,    77,
     78,    79,    80,    81,    82,    83,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    84,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    85,    86,     0,    87,  1160,    88,    89,    90,
     91,     4,     5,     0,     0,     0,     6,     7,     8,     0,
      9,    10,    11,    12,    13,    14,    15,    16,     0,    17,
      0,     0,    18,    19,    20,    21,    22,     0,     0,     0,
     23,    24,    25,     0,    26,    27,    28,    29,     0,     0,
      0,    30,    31,    32,     0,    33,     0,    34,     0,    35,
      0,     0,    36,     0,     0,     0,    37,    38,    39,    40,
     41,    42,     0,    44,    45,     0,     0,    46,     0,     0,
     48,    49,    50,    51,     0,     0,     0,     0,     0,     0,
     52,     0,    53,    54,    55,   543,    57,    58,    59,    60,
      0,     0,     0,    62,    63,     0,    64,    65,    66,    67,
     68,    69,    70,    71,    72,    73,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    74,
     75,    76,    77,    78,    79,    80,    81,    82,    83,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    84,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    85,    86,     0,    87,  1194,
     88,    89,    90,    91,     4,     5,     0,     0,     0,     6,
      7,     8,     0,     9,    10,    11,    12,    13,    14,    15,
     16,     0,    17,     0,     0,    18,    19,    20,    21,    22,
      0,     0,     0,    23,    24,    25,     0,    26,    27,    28,
     29,     0,     0,     0,    30,    31,    32,     0,    33,     0,
     34,     0,    35,     0,     0,    36,     0,     0,     0,    37,
     38,    39,    40,    41,    42,     0,    44,    45,     0,     0,
     46,     0,     0,    48,    49,    50,    51,     0,     0,     0,
      0,     0,     0,    52,     0,    53,    54,    55,   543,    57,
     58,    59,    60,     0,     0,     0,    62,    63,     0,    64,
     65,    66,    67,    68,    69,    70,    71,    72,    73,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    74,    75,    76,    77,    78,    79,    80,    81,
     82,    83,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    84,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    85,    86,
      0,    87,  1204,    88,    89,    90,    91,     4,     5,     0,
      0,     0,     6,     7,     8,     0,     9,    10,    11,    12,
     13,    14,    15,    16,     0,    17,     0,     0,    18,    19,
     20,    21,    22,     0,     0,     0,    23,    24,    25,     0,
     26,    27,    28,    29,     0,     0,     0,    30,    31,    32,
      0,    33,     0,    34,     0,    35,     0,     0,    36,     0,
      0,     0,    37,    38,    39,    40,    41,    42,     0,    44,
     45,     0,     0,    46,     0,     0,    48,    49,    50,    51,
      0,     0,     0,     0,     0,     0,    52,     0,    53,    54,
     55,   543,    57,    58,    59,    60,     0,     0,     0,    62,
     63,     0,    64,    65,    66,    67,    68,    69,    70,    71,
     72,    73,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    74,    75,    76,    77,    78,
     79,    80,    81,    82,    83,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    84,     0,     0,     0,     0,     0,
      0,     0,   784,     0,     0,     0,     0,     0,     0,     0,
      0,    85,    86,     0,    87,     0,    88,    89,    90,    91,
   1056,    12,    13,    14,     0,     0,     0,     0,     0,     0,
    220,   221,   222,   223,   224,   225,   226,   227,   228,   229,
      0,   230,   231,   232,   233,   234,   235,   236,   237,   238,
    239,   240,   241,   242,   243,   244,   245,   246,   247,   248,
    249,   250,   251,   252,   253,   254,   255,   256,   257,   258,
    259,   260,   261,   262,   263,   264,   265,   266,   267,  1057,
    269,   270,   271,   272,   273,     0,     0,     0,   274,   275,
    276,   277,   278,     0,   279,   280,   281,   282,   283,   284,
    285,   286,  1058,  1059,   289,   290,   291,   292,   293,   294,
    295,   296,   297,     4,     5,     0,     0,     0,     6,     7,
      8,     0,     9,    10,    11,    12,    13,    14,    15,    16,
      0,    17,     0,     0,    18,    19,    20,    21,    22,     0,
      0,     0,    23,    24,    25,     0,    26,    27,    28,    29,
      0,     0,     0,    30,    31,    32,     0,    33,     0,    34,
      0,    35,     0,     0,    36,     0,     0,     0,    37,    38,
     39,    40,    41,    42,   788,    44,    45,     0,     0,    46,
      0,     0,    48,    49,     0,     0,     0,     0,     0,     0,
      0,     0,   134,     0,    53,    54,    55,     0,     0,     0,
      0,     0,     0,     0,     0,    62,    63,     0,    64,    65,
     66,    67,    68,    69,    70,    71,    72,    73,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    74,    75,    76,    77,    78,    79,    80,    81,    82,
     83,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     84,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    85,    86,     0,
     87,     0,    88,    89,    90,    91,     4,     5,     0,     0,
      0,     6,     7,     8,     0,     9,    10,   132,    12,    13,
     14,    15,     0,     0,    17,     0,     0,    18,    19,    20,
     21,    22,     0,     0,     0,    23,    24,    25,     0,    26,
     27,    28,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    37,     0,     0,     0,    41,    42,     0,     0,     0,
      0,     0,    46,     0,     0,     0,   133,     0,     0,     0,
      0,     0,     0,     0,     0,   134,     0,     0,    54,    55,
      0,     0,     0,     0,     0,     0,     0,     0,   332,    63,
      0,    64,    65,    66,    67,    68,    69,    70,    71,    72,
     73,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    74,    75,    76,    77,    78,    79,
     80,    81,    82,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    84,     0,     0,     0,     0,     0,   333,
      0,     0,     0,     0,   334,   335,     0,     0,     0,     0,
      0,    86,     0,     0,     0,    88,    89,    90,    91,     4,
      5,     0,     0,     0,     6,     7,     8,     0,     9,    10,
    132,    12,    13,    14,    15,     0,     0,    17,     0,     0,
     18,    19,    20,    21,    22,     0,     0,     0,    23,    24,
     25,     0,    26,    27,    28,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    37,     0,     0,     0,    41,    42,
      0,     0,     0,     0,     0,    46,     0,     0,     0,   133,
      0,     0,     0,     0,     0,     0,     0,     0,   134,     0,
      0,    54,    55,     0,     0,     0,     0,     0,     0,     0,
      0,    62,    63,     0,    64,    65,    66,    67,    68,    69,
     70,    71,    72,    73,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    74,    75,    76,
     77,    78,    79,    80,    81,    82,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    84,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,   334,   335,     0,
      0,     0,     0,     0,    86,     0,     0,     0,    88,    89,
     90,    91,     4,     5,     0,     0,     0,     6,     7,     8,
      0,     9,    10,   132,    12,    13,    14,    15,     0,     0,
     17,     0,     0,    18,    19,    20,    21,    22,     0,     0,
      0,    23,    24,    25,     0,    26,    27,    28,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    37,     0,     0,
      0,    41,    42,     0,     0,     0,     0,     0,    46,     0,
      0,     0,   133,     0,     0,     0,     0,     0,     0,     0,
      0,   134,     0,     0,    54,    55,     0,     0,     0,     0,
      0,     0,     0,     0,   710,    63,     0,    64,    65,    66,
     67,    68,    69,    70,    71,    72,    73,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     74,    75,    76,    77,    78,    79,    80,    81,    82,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    84,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
    334,   335,     0,     0,     0,     0,     0,    86,     0,     0,
      0,    88,    89,    90,    91,     4,     5,     0,     0,     0,
      6,     7,     8,     0,     9,    10,   132,    12,    13,    14,
     15,     0,     0,    17,     0,     0,    18,    19,    20,    21,
     22,     0,     0,     0,    23,    24,    25,     0,    26,    27,
     28,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     37,     0,     0,     0,    41,    42,     0,     0,     0,     0,
      0,    46,     0,     0,     0,   133,     0,     0,     0,     0,
      0,     0,     0,     0,   134,     0,     0,    54,    55,     0,
      0,     0,     0,     0,     0,     0,     0,    62,    63,     0,
     64,    65,    66,    67,    68,    69,    70,    71,    72,    73,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    74,    75,    76,    77,    78,    79,    80,
     81,    82,   478,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    84,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     86,     0,     0,     0,    88,    89,    90,    91,     4,     5,
      0,     0,     0,     6,     7,     8,     0,     9,    10,   132,
     12,    13,    14,    15,     0,     0,    17,   536,     0,    18,
     19,    20,    21,    22,     0,     0,     0,    23,    24,    25,
      0,    26,    27,    28,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,    37,     0,     0,     0,    41,    42,     0,
      0,     0,     0,     0,    46,     0,     0,     0,   133,     0,
      0,     0,     0,     0,     0,     0,     0,   134,     0,     0,
     54,    55,     0,     0,     0,     0,     0,     0,     0,     0,
     62,    63,     0,    64,    65,    66,    67,    68,    69,    70,
     71,    72,    73,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    74,    75,    76,    77,
     78,    79,    80,    81,    82,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    84,     0,     0,     0,     0,
      0,     0,     0,     0,     0,   364,   365,   366,   367,   368,
    369,   370,   371,    86,     0,     0,     0,    88,    89,    90,
     91,     4,     5,     0,     0,     0,     6,     7,     8,     0,
      9,    10,   132,    12,    13,    14,    15,     0,     0,    17,
    375,     0,    18,    19,    20,    21,    22,     0,     0,     0,
     23,    24,    25,     0,    26,    27,    28,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    37,     0,     0,     0,
     41,    42,     0,     0,     0,     0,     0,    46,     0,     0,
      0,   133,     0,     0,     0,     0,     0,     0,     0,     0,
    134,     0,     0,    54,    55,     0,     0,     0,     0,     0,
      0,     0,     0,    62,    63,     0,    64,    65,    66,    67,
     68,    69,    70,    71,    72,    73,     0,   378,   379,   380,
    381,   382,   383,   384,   385,   386,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    74,
     75,    76,    77,    78,    79,    80,    81,    82,     0,     0,
      0,     0,     0,   388,     0,   389,     0,     0,    84,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,    86,   641,     0,     0,
     88,    89,    90,    91,     4,     5,     0,     0,     0,     6,
      7,     8,     0,     9,    10,   132,    12,    13,    14,    15,
      0,     0,    17,     0,     0,    18,    19,    20,    21,    22,
      0,     0,     0,    23,    24,    25,     0,    26,    27,    28,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    37,
      0,     0,     0,    41,    42,     0,     0,     0,     0,     0,
     46,     0,     0,     0,   133,     0,     0,     0,     0,     0,
      0,     0,     0,   134,     0,     0,    54,    55,     0,     0,
      0,     0,     0,     0,     0,     0,    62,    63,     0,    64,
     65,    66,    67,    68,    69,    70,    71,    72,    73,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    74,    75,    76,    77,    78,    79,    80,    81,
     82,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    84,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,    86,
    647,     0,     0,    88,    89,    90,    91,     4,     5,     0,
      0,     0,     6,     7,     8,     0,     9,    10,   132,    12,
     13,    14,    15,     0,     0,    17,     0,     0,    18,    19,
     20,    21,    22,     0,     0,     0,    23,    24,    25,     0,
     26,    27,    28,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    37,     0,     0,     0,    41,    42,     0,     0,
      0,     0,     0,    46,     0,     0,     0,   133,     0,     0,
      0,     0,     0,     0,     0,     0,   134,     0,     0,    54,
     55,     0,     0,     0,     0,     0,     0,     0,     0,    62,
     63,     0,    64,    65,    66,    67,    68,    69,    70,    71,
     72,    73,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    74,    75,    76,    77,    78,
     79,    80,    81,    82,   753,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    84,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    86,     0,     0,     0,    88,    89,    90,    91,
      4,     5,     0,     0,     0,     6,     7,     8,     0,     9,
     10,   132,    12,    13,    14,    15,     0,     0,    17,     0,
      0,    18,    19,    20,    21,    22,     0,     0,     0,    23,
     24,    25,     0,    26,    27,    28,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,   857,    37,     0,     0,     0,    41,
     42,     0,     0,     0,     0,     0,    46,     0,     0,     0,
    133,     0,     0,     0,     0,     0,     0,     0,     0,   134,
      0,     0,    54,    55,     0,     0,     0,     0,     0,     0,
      0,     0,    62,    63,     0,    64,    65,    66,    67,    68,
     69,    70,    71,    72,    73,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    74,    75,
     76,    77,    78,    79,    80,    81,    82,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,    84,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,    86,     0,     0,     0,    88
    };
  }

  private static short[] yytable_init_3()
  {
    return new short[]
    {
     89,    90,    91,     4,     5,     0,     0,     0,     6,     7,
      8,     0,     9,    10,   132,    12,    13,    14,    15,     0,
      0,    17,     0,     0,    18,    19,    20,    21,    22,     0,
      0,     0,    23,    24,    25,     0,    26,    27,    28,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    37,     0,
      0,     0,    41,    42,     0,     0,     0,     0,     0,    46,
      0,     0,     0,   133,     0,     0,     0,     0,     0,     0,
      0,     0,   134,     0,     0,    54,    55,     0,     0,     0,
      0,     0,     0,     0,     0,    62,    63,     0,    64,    65,
     66,    67,    68,    69,    70,    71,    72,    73,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    74,    75,    76,    77,    78,    79,    80,    81,    82,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
     84,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,    86,     0,
      0,     0,    88,    89,    90,    91,     4,     5,     0,     0,
      0,     6,     7,     8,     0,     9,    10,   132,    12,    13,
     14,    15,     0,     0,    17,     0,     0,    18,    19,    20,
     21,    22,     0,     0,     0,    23,    24,    25,     0,    26,
     27,    28,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,    37,     0,     0,     0,    41,    42,     0,     0,     0,
      0,     0,    46,     0,     0,     0,   133,     0,     0,     0,
      0,     0,     0,     0,     0,   134,     0,     0,    54,    55,
      0,     0,     0,     0,     0,     0,     0,     0,    62,    63,
      0,    64,    65,    66,    67,    68,    69,    70,    71,    72,
     73,   361,  1036,   362,   363,   364,   365,   366,   367,   368,
    369,   370,   371,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,    74,    75,    76,    77,    78,    79,
     80,    81,    82,     0,   372,   373,   374,     0,     0,     0,
    375,     0,     0,    84,     0,     0,     0,     0,     0,   361,
      0,   362,   363,   364,   365,   366,   367,   368,   369,   370,
    371,   164,     0,     0,     0,    88,    89,    90,    91,   361,
      0,   362,   363,   364,   365,   366,   367,   368,   369,   370,
    371,     0,   372,   373,   374,     0,     0,     0,   375,     0,
      0,     0,   943,     0,     0,     0,     0,     0,     0,     0,
   -358,     0,   372,   373,   374,     0,   944,     0,   375,   815,
    816,   817,   818,   819,   820,   821,   822,   823,   824,   945,
      0,     0,     0,     0,     0,   376,   377,   378,   379,   380,
    381,   382,   383,   384,   385,   386,     0,     0,     0,     0,
      0,     0,     0,    73,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,   387,   388,     0,   389,   390,   391,     0,     0,
      0,     0,  1037,   376,   377,   378,   379,   380,   381,   382,
    383,   384,   385,   386,     0,     0,     0,     0,     0,     0,
      0,     0,     0,   376,   377,   378,   379,   380,   381,   382,
    383,   384,   385,   386,     0,     0,     0,  1012,     0,     0,
    387,   388,     0,   389,   390,   391,     0,     0,     0,     0,
    392,     0,     0,     0,     0,     0,     0,     0,     0,     0,
    387,   388,     0,   389,   390,   391,     0,     0,     0,   361,
    530,   362,   363,   364,   365,   366,   367,   368,   369,   370,
    371,   361,     0,   362,   363,   364,   365,   366,   367,   368,
    369,   370,   371,     0,     0,     0,     0,     0,     0,     0,
      0,     0,   372,   373,   374,     0,     0,     0,   375,     0,
      0,     0,     0,     0,   372,   373,   374,     0,     0,     0,
    375,     0,     0,     0,     0,     0,     0,     0,   361,     0,
    362,   363,   364,   365,   366,   367,   368,   369,   370,   371,
    361,     0,   362,   363,   364,   365,   366,   367,   368,   369,
    370,   371,     0,     0,     0,     0,     0,     0,     0,     0,
      0,   372,   373,   374,     0,     0,     0,   375,     0,     0,
      0,     0,     0,   372,   373,   374,     0,     0,     0,   375,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,   376,   377,   378,   379,   380,   381,   382,
    383,   384,   385,   386,     0,   376,   377,   378,   379,   380,
    381,   382,   383,   384,   385,   386,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
    387,   388,     0,   389,   390,   391,     0,     0,     0,     0,
   1203,     0,   387,   388,     0,   389,   390,   391,     0,     0,
      0,   888,   376,   377,   378,   379,   380,   381,   382,   383,
    384,   385,   386,     0,   376,   377,   378,   379,   380,   381,
    382,   383,   384,   385,   386,     0,     0,   361,     0,   362,
    363,   364,   365,   366,   367,   368,   369,   370,   371,   387,
    388,     0,   389,   390,   391,     0,     0,  -408,     0,     0,
      0,   387,   388,     0,   389,   390,   391,     0,     0,  -405,
    372,   373,   374,     0,     0,     0,   375,     0,     0,     0,
    361,     0,   362,   363,   364,   365,   366,   367,   368,   369,
    370,   371,   361,   721,   362,   363,   364,   365,   366,   367,
    368,   369,   370,   371,     0,     0,     0,     0,     0,     0,
      0,     0,     0,   372,   373,   374,     0,     0,     0,   375,
      0,     0,     0,     0,     0,   372,   373,   374,     0,     0,
      0,   375,     0,     0,     0,     0,     0,   657,     0,   361,
      0,   362,   363,   364,   365,   366,   367,   368,   369,   370,
    371,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,   376,   377,   378,   379,   380,   381,   382,   383,   384,
    385,   386,   372,   373,   374,     0,     0,     0,   375,     0,
      0,     0,     0,     0,   552,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,   387,   388,
      0,   389,   390,   391,   376,   377,   378,   379,   380,   381,
    382,   383,   384,   385,   386,     0,   376,   377,   378,   379,
    380,   381,   382,   383,   384,   385,   386,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,   387,   388,     0,   389,   390,   391,     0,     0,     0,
      0,     0,     0,   387,   388,     0,   389,   390,   391,     0,
      0,     0,     0,   376,   377,   378,   379,   380,   381,   382,
    383,   384,   385,   386,   361,     0,   362,   363,   364,   365,
    366,   367,   368,   369,   370,   371,   361,     0,   362,   363,
    364,   365,   366,   367,   368,   369,   370,   371,     0,     0,
    387,   388,     0,   389,   390,   391,     0,     0,   373,   374,
      0,     0,     0,   375,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,   375,     0,     0,     0,   361,
      0,   362,   363,   364,   365,   366,   367,   368,   369,   370,
    371,   361,     0,   362,   363,   364,   365,   366,   367,   368,
    369,   370,   371,     0,   362,   363,   364,   365,   366,   367,
    368,   369,   370,   371,   374,     0,     0,     0,   375,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
    375,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,   375,     0,     0,     0,     0,     0,     0,   376,   377,
    378,   379,   380,   381,   382,   383,   384,   385,   386,     0,
    376,   377,   378,   379,   380,   381,   382,   383,   384,   385,
    386,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,   420,     0,   387,   388,     0,   389,   390,
    391,     0,     0,     0,     0,     0,     0,   387,   388,     0,
    389,   390,   391,   376,   377,   378,   379,   380,   381,   382,
    383,   384,   385,   386,     0,   376,   377,   378,   379,   380,
    381,   382,   383,   384,   385,   386,   376,   377,   378,   379,
    380,   381,   382,   383,   384,   385,   386,     0,     0,     0,
    387,   388,     0,   389,   390,   391,     0,     0,     0,     0,
      0,     0,   387,   388,     0,   389,   390,   391,     0,     0,
      0,   218,     0,   387,   388,    15,   389,   390,   391,     0,
      0,   220,   221,   222,   223,   224,   225,   226,   227,   228,
    229,     0,   230,   231,   232,   233,   234,   235,   236,   237,
    238,   239,   240,   241,   242,   243,   244,   245,   246,   247,
    248,   249,   250,   251,   252,   253,   254,   255,   256,   257,
    258,   259,   260,   261,   262,   263,   264,   265,   266,   267,
    268,   269,   270,   271,   272,   273,     0,     0,     0,   274,
    275,   276,   277,   278,     0,   279,   280,   281,   282,   283,
    284,   285,   286,   287,   288,   289,   290,   291,   292,   293,
    294,   295,   296,   297,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,   943,     0,     0,     0,     0,     0,
      0,     0,  -358,     0,     0,     0,     0,     0,   944,     0,
      0,   815,   816,   817,   818,   819,   820,   821,   822,   823,
    824,   945,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,   218,    73,     0,   602,    15,     0,
      0,     0,    91,     0,   220,   221,   222,   223,   224,   225,
    226,   227,   228,   229,     0,   230,   231,   232,   233,   234,
    235,   236,   237,   238,   239,   240,   241,   242,   243,   244,
    245,   246,   247,   248,   249,   250,   251,   252,   253,   254,
    255,   256,   257,   258,   259,   260,   261,   262,   263,   264,
    265,   266,   267,   268,   269,   270,   271,   272,   273,  1046,
      0,     0,   274,   275,   276,   277,   278,     0,   279,   280,
    281,   282,   283,   284,   285,   286,   287,   288,   289,   290,
    291,   292,   293,   294,   295,   296,   297,     0,   132,    12,
     13,    14,    15,     0,     0,    17,     0,     0,     0,   132,
     12,    13,    14,    15,     0,     0,    17,     0,   943,     0,
    306,     0,     0,     0,     0,     0,  -358,     0,     0,     0,
      0,   306,   944,     0,     0,   815,   816,   817,   818,   819,
    820,   821,   822,   823,   824,   945,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,   151,     0,     0,
    606,     0,     0,     0,     0,    91,   134,     0,   151,    73,
      0,     0,     0,     0,     0,     0,     0,   134,     0,   755,
     63,     0,    64,    65,    66,    67,    68,    69,    70,    71,
     72,    63,     0,    64,    65,    66,    67,    68,    69,    70,
     71,    72,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,  1064,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,   334,   335,     0,     0,     0,
      0,     0,   307,     0,     0,     0,   756,     0,    90,    91,
      0,     0,     0,   307,     0,     0,     0,   308,     0,    90,
     91,   431,    12,    13,    14,     0,     0,     0,     0,     0,
      0,   220,   221,   222,   223,   224,   225,   226,   227,   228,
    229,     0,   230,   231,   232,   233,   234,   235,   236,   237,
    238,   239,   240,   241,   242,   243,   244,   245,   246,   247,
    248,   249,   250,   251,   252,   253,   254,   255,   256,   257,
    258,   259,   260,   261,   262,   263,   264,   265,   266,   267,
   1083,   269,   270,   271,   272,   273,     0,     0,     0,   274,
    275,   276,   277,   278,     0,   279,   280,   281,   282,   283,
    284,   285,   286,   287,   288,   289,   290,   291,   292,   293,
    294,   295,   296,   297,     0,     0,   943,     0,     0,     0,
      0,     0,     0,     0,  -358,     0,     0,     0,     0,     0,
    944,     0,     0,   815,   816,   817,   818,   819,   820,   821,
    822,   823,   824,   945,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,   431,    12,
     13,    14,     0,     0,     0,     0,     0,    73,   220,   221,
    222,   223,   224,   225,   226,   227,   228,   229,  1084,   230,
    231,   232,   233,   234,   235,   236,   237,   238,   239,   240,
    241,   242,   243,   244,   245,   246,   247,   248,   249,   250,
    251,   252,   253,   254,   255,   256,   257,   258,   259,   260,
    261,   262,   263,   264,   265,   266,   267,  1083,   269,   270,
    271,   272,   273,     0,     0,     0,   274,   275,   276,   277,
    278,  1104,   279,   280,   281,   282,   283,   284,   285,   286,
    287,   288,   289,   290,   291,   292,   293,   294,   295,   296,
    297,   943,     0,     0,     0,     0,     0,     0,     0,  -358,
      0,     0,     0,     0,     0,   944,     0,     0,   815,   816,
    817,   818,   819,   820,   821,   822,   823,   824,   945,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,    73,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,  1117,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,     0,     0,     0,     0,
      0,     0,     0,     0,     0,     0,  1143,     0,     0,     0,
      0,     0,     0,     0,     0,     0,   220,   221,   222,   223,
    224,   225,   226,   227,   228,   229,  1112,   230,   231,   232,
    233,   234,   235,   236,   237,   238,   239,   240,   241,   242,
    243,   244,   245,   246,   247,   248,   249,   250,   251,   252,
    253,   254,   255,   256,   257,   258,   259,   260,   261,   262,
    263,   264,   265,   266,   267,   815,   816,   817,   818,   819,
    820,   821,   822,   823,   824,   275,   276,   277,   278,     0,
    279,   280,   281,   282,   283,   284,   285,   286,   287,   288,
    289,   290,   291,   292,   293,   294,   295,   296,   297,   218,
      0,     0,   219,     0,     0,     0,     0,     0,     0,   220,
    221,   222,   223,   224,   225,   226,   227,   228,   229,     0,
    230,   231,   232,   233,   234,   235,   236,   237,   238,   239,
    240,   241,   242,   243,   244,   245,   246,   247,   248,   249,
    250,   251,   252,   253,   254,   255,   256,   257,   258,   259,
    260,   261,   262,   263,   264,   265,   266,   267,   268,   269,
    270,   271,   272,   273,     0,     0,     0,   274,   275,   276,
    277,   278,     0,   279,   280,   281,   282,   283,   284,   285,
    286,   287,   288,   289,   290,   291,   292,   293,   294,   295,
    296,   297,   218,     0,     0,     0,     0,     0,     0,     0,
      0,     0,   220,   221,   222,   223,   224,   225,   226,   227,
    228,   229,     0,   230,   231,   232,   233,   234,   235,   236,
    237,   238,   239,   240,   241,   242,   243,   244,   245,   246,
    247,   248,   249,   250,   251,   252,   253,   254,   255,   256,
    257,   258,   259,   260,   261,   262,   263,   264,   265,   266,
    267,   268,   269,   270,   271,   272,   273,     0,     0,     0,
    274,   275,   276,   277,   278,     0,   279,   280,   281,   282,
    283,   284,   285,   286,   287,   288,   289,   290,   291,   292,
    293,   294,   295,   296,   297,  1100,     0,     0,     0,     0,
      0,     0,     0,     0,     0,   220,   221,   222,   223,   224,
    225,   226,   227,   228,   229,     0,   230,   231,   232,   233,
    234,   235,   236,   237,   238,   239,   240,   241,   242,   243,
    244,   245,   246,   247,   248,   249,   250,   251,   252,   253,
    254,   255,   256,   257,   258,   259,   260,   261,   262,   263,
    264,   265,   266,   267,   268,   269,   270,   271,   272,   273,
      0,     0,     0,   274,   275,   276,   277,   278,     0,   279,
    280,   281,   282,   283,   284,   285,   286,   287,   288,   289,
    290,   291,   292,   293,   294,   295,   296,   297
    };
  }

private static final short[] yycheck_ = yycheck_init();
  private static final short[] yycheck_init()
  {
    // split by tools/parser/post_bison.py: one initializer of 11318 entries is too large a method
    short[] table = new short[11318];
    System.arraycopy(yycheck_init_0(), 0, table, 0, 3000);
    System.arraycopy(yycheck_init_1(), 0, table, 3000, 3000);
    System.arraycopy(yycheck_init_2(), 0, table, 6000, 3000);
    System.arraycopy(yycheck_init_3(), 0, table, 9000, 2318);
    return table;
  }

  private static short[] yycheck_init_0()
  {
    return new short[]
    {
      2,   115,   116,    61,    94,   116,     2,    47,     2,   831,
     74,    75,   189,     6,   198,     4,     5,     6,     7,     8,
    303,   304,   724,   137,    26,   308,   137,   778,     6,    18,
     19,     4,    21,    22,    23,    24,    25,     6,    27,    49,
     26,    30,    39,    27,   980,   135,   982,    44,   768,    38,
     39,   521,   772,     5,     7,    44,    27,    46,    62,    44,
     74,    75,     5,     7,    27,   155,    29,   716,    74,    75,
    152,    23,    24,    25,    26,    62,   306,     2,    83,    84,
     23,    24,    25,    26,    94,   734,    91,    76,    77,    78,
     79,    80,    81,    82,    83,   686,   178,    86,    94,    88,
     94,    65,    66,  1025,    27,  1027,    31,    74,    75,    14,
     15,    16,    23,    27,     0,   101,    27,    27,    73,    29,
     27,    52,    29,   133,   163,   135,    27,     6,    29,   469,
     82,   833,   954,    23,    24,    25,    26,    27,   177,    44,
     12,    13,    14,    15,    16,   375,   210,    11,    12,    13,
     14,    15,    16,   105,   106,   762,   145,    27,   198,   877,
    164,    92,   105,   106,   153,    23,    27,   507,    29,   155,
    156,   511,    44,     6,    27,   164,    29,   164,   167,    26,
     44,   170,   167,    62,   177,   525,   175,   176,   177,   177,
    179,   180,    82,   944,   177,   179,   210,   160,   161,   162,
    184,   179,   152,   134,   210,   171,   179,   176,  1135,    74,
     75,  1133,   178,   184,   829,   968,   180,   687,   171,   810,
    940,  1011,   211,   212,   942,   177,   176,   171,    26,   179,
    161,    65,    66,    91,   177,  1162,  1158,  1159,   161,   162,
     71,    72,  1178,   210,   152,   963,   853,   838,   855,     7,
     48,   161,   162,    27,   161,   162,   170,   171,  1011,  1181,
    161,   162,   167,   327,   177,  1187,   549,  1189,   179,    27,
    178,  1061,   336,   184,   138,   139,   183,  1199,   177,    12,
     13,    14,    15,    16,   899,   900,    23,   177,   402,    26,
    160,   161,   162,    91,   184,   167,  1014,    71,    72,  1121,
    161,   162,   176,   167,   306,   179,   177,   160,   161,   162,
   1028,    44,  1102,   327,   303,   304,   500,   177,   307,   308,
    306,   327,   336,    71,    72,    23,    24,    25,    26,   331,
    336,   152,   163,   177,    82,   331,   951,   326,   396,   397,
    350,   152,   171,  1165,   333,   210,   180,    49,    50,   178,
     23,    24,    25,    26,    49,    50,    51,   178,   347,    26,
    327,   976,   977,   978,   979,   177,   784,   178,   116,   336,
    788,  1122,   361,   362,   363,   364,   365,   366,   367,   368,
    369,   370,   371,   372,   373,   374,   563,   376,   377,   378,
    379,   380,   381,   382,   383,   384,   385,   386,   387,   388,
    389,   390,   391,   400,   177,   138,   139,   105,   106,    82,
    174,   400,   176,   402,   403,   404,   405,   406,   407,   408,
    409,   410,   411,   412,   413,   414,   170,   171,   417,   769,
     23,   420,   357,   430,   167,    23,   446,   439,   552,   394,
    399,   430,   177,   432,   433,   179,   435,   436,   437,   438,
   1152,   440,   177,   439,    23,   545,   445,    26,   305,   306,
    500,   177,   327,   881,   882,   883,   884,   177,   427,   428,
    401,   336,   461,   756,    23,    24,    25,    26,   467,    23,
     24,    25,    26,    27,   174,   474,  1188,   827,   178,   478,
    177,   493,    23,    24,    25,    26,   177,   493,    23,    24,
    489,    26,    71,   174,    73,   150,   151,   178,   306,   163,
    150,   151,    83,    84,   177,   504,   447,   857,   163,   859,
     91,   861,   163,   163,   807,   174,   175,   163,   375,   178,
    870,    97,   177,    82,   167,   545,   181,   177,    82,    83,
     84,   181,   174,   657,   176,   116,    71,    91,    73,   545,
    174,   545,   176,   617,   163,   179,   105,   106,    71,    72,
    549,   516,   845,   552,   644,   554,   646,   744,   648,    54,
    747,   560,   116,   562,   105,   106,   565,   375,    64,    65,
     66,   875,    82,    83,    84,    85,    86,    87,    88,    89,
     90,    91,   439,   657,   176,   526,   527,   176,   396,   397,
    398,   399,   176,   617,   150,   151,    23,    24,    25,    26,
    674,   617,   174,   602,   176,     4,   116,   606,    83,    84,
    174,   175,   611,   174,   178,   176,    91,   425,   177,   427,
    428,   429,    97,   177,   174,   181,   176,   176,   305,   306,
    184,   439,   174,   657,   176,   600,   177,   711,   150,   151,
    617,   657,   176,   947,   643,   644,   174,   646,   176,   648,
    674,   163,   956,   652,     4,    82,   655,   656,   674,   180,
    181,   150,   151,   850,   605,   177,    76,    77,   609,   181,
    180,   178,   613,   614,   163,    23,   688,   676,   105,   106,
    657,    23,   688,   757,   688,   150,   151,   711,    23,   150,
    151,   174,   181,   176,   502,   711,   661,   674,   375,   150,
    151,  1005,   163,   882,   883,   704,   893,   719,    23,  1013,
    722,   179,   724,   719,   713,   650,   181,   176,   653,   684,
    181,  1071,   721,   177,   848,   825,   175,   150,   151,   177,
    181,   918,   177,   757,   711,   174,    23,    24,   737,    26,
    163,   757,   617,   177,    23,    24,    25,    26,    27,   936,
    174,   324,   176,   688,   753,   328,   182,   756,   181,   977,
    978,  1065,   439,    89,    90,   764,   177,    13,     6,   342,
   1074,   344,   345,   346,   848,   176,     6,    23,     6,     6,
    757,    27,   657,     6,     6,   797,    32,    64,    65,    66,
      6,   797,     6,   797,     6,     6,     6,     6,     6,   674,
    799,   174,   174,    82,    83,    84,   993,   994,   807,   177,
    176,   174,    91,   174,   779,   179,    23,   176,    97,   174,
    176,   833,    27,   680,   848,   760,   683,   839,   101,   841,
    101,   796,   848,   839,   691,   841,   711,   116,     6,   179,
    178,   178,  1029,   842,   843,  1149,   845,   947,   175,  1036,
   1037,   174,    23,  1157,    23,   180,   177,     4,    23,   177,
   1164,   177,   797,   875,   177,   177,   175,    71,    72,    73,
   1174,   848,   175,   178,   178,    62,   811,   179,    82,    83,
     84,   179,   757,   178,   178,   897,  1010,    91,   179,  1010,
      4,   175,   177,    97,    98,    99,   100,    32,   177,   102,
    175,   177,  1026,    62,   178,   184,   178,   177,   176,   921,
    174,   177,   116,   176,   849,   921,   176,   174,   176,   174,
   1107,   778,     7,   179,  1111,   924,   891,  1114,     7,   171,
     71,    72,   931,   176,   933,     6,   176,   178,  1006,   174,
    177,    82,    83,    84,   956,   910,   952,   178,   180,   175,
     91,   176,   171,   174,   174,   967,    97,    98,    99,   100,
    177,   967,    23,     9,    10,    11,    12,    13,    14,    15,
     16,     6,   174,   848,   180,   116,   988,   174,   943,   179,
    179,  1049,   988,   179,   170,  1109,   180,  1055,   178,     7,
   1002,     7,   171,  1005,   178,    79,  1002,   996,    44,  1186,
    999,  1013,   176,   680,   176,   178,   683,   152,   685,   152,
    180,  1198,   178,    71,   691,   178,    73,   179,  1086,   876,
    152,   171,   177,   165,   176,   179,  1038,  1039,   176,    27,
     27,     4,  1038,  1039,   178,     4,   176,     4,     4,     4,
      4,     4,  1166,   179,    27,  1166,   174,   176,   179,   179,
    985,   163,   987,  1065,   176,  1123,   176,    62,    80,   163,
    179,   178,  1074,  1075,  1164,  1077,  1078,    27,   176,  1075,
      4,  1077,  1078,   179,   177,    23,   178,  1145,   935,   177,
   1121,   178,  1081,   691,    94,    47,  1051,   944,   198,   135,
    136,   868,   138,   139,  1093,   500,   178,   674,   848,  1098,
   1099,   778,  1067,   931,   987,   897,  1105,   784,   825,   951,
    788,   788,   900,   164,   502,   505,  1086,     4,   947,  1156,
   1095,   167,  1164,   169,  1061,  1137,   842,   474,  1140,   655,
   1142,  1137,  1097,   490,  1140,  1134,  1142,  1149,  1102,   554,
   1152,   155,  1109,   635,   397,  1157,   676,    -1,    -1,    -1,
     -1,  1150,   829,    -1,    -1,    -1,    -1,    -1,    -1,  1124,
     -1,  1126,  1174,    -1,  1129,  1130,    82,    83,    84,    85,
     86,    87,    88,    89,    90,    91,  1188,    -1,  1190,    -1,
     -1,    -1,    -1,    -1,  1190,  1042,    -1,    -1,    -1,  1201,
     -1,    -1,  1049,    -1,    -1,  1201,    -1,  1196,    -1,   876,
    116,    -1,    -1,  1168,   881,   882,   883,   884,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,  1180,    -1,    -1,    -1,    -1,
     -1,    -1,   899,   900,    -1,    -1,    -1,    -1,    -1,  1086,
    117,   118,   119,   120,   121,   122,   123,   124,   125,   126,
    127,   128,    -1,    82,    83,    84,    85,    86,    87,    88,
     89,    90,    91,   140,   141,    -1,    -1,    -1,   935,    -1,
     -1,    -1,   178,   150,   151,  1122,    -1,   944,    -1,    -1,
     -1,    -1,    -1,    -1,   951,    -1,   163,   116,    -1,    -1,
     -1,   168,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   968,    -1,    -1,   181,    -1,    -1,    -1,    65,   976,
    977,   978,   979,    -1,    -1,    -1,    73,    -1,    -1,    -1,
     -1,    -1,    79,    -1,    -1,    82,    83,    84,    85,    86,
     87,    88,    89,    90,    91,    92,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,  1011,    -1,    -1,    -1,    -1,    -1,
     -1,   180,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   116,
     -1,    -1,     7,     8,     9,    10,    11,    12,    13,    14,
     15,    16,    -1,    -1,     6,  1042,    -1,    -1,    -1,    -1,
     12,    13,  1049,    -1,    -1,    17,    18,    19,    -1,    21,
     22,    23,    24,    25,    26,    27,    28,    -1,    30,    44,
     -1,    33,    34,    35,    36,    37,    -1,    -1,    -1,    41,
     42,    43,    -1,    45,    46,    47,    48,    -1,    -1,  1086,
     52,    53,    54,   180,    56,    -1,    58,    -1,    60,    -1,
     -1,    63,    -1,    -1,    -1,    67,    68,    69,    70,    71,
     72,    -1,    74,    75,    -1,    -1,    78,    -1,    -1,    81,
     82,    -1,    -1,    -1,    -1,  1122,    -1,    -1,    -1,    91,
     -1,    93,    94,    95,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   104,   105,    -1,   107,   108,   109,   110,   111,
    112,   113,   114,   115,   116,   130,   131,   132,   133,   134,
    135,   136,   137,   138,   139,    82,    83,    84,    85,    86,
     87,    88,    89,    90,    91,    -1,    -1,    -1,   140,   141,
    142,   143,   144,   145,   146,   147,   148,   149,    -1,    71,
     -1,    73,   167,    -1,   169,   170,   171,   159,    -1,   116,
     82,    83,    84,    85,    86,    87,    88,    89,    90,    91,
     -1,    -1,    -1,    -1,   176,   177,    -1,   179,     6,   181,
    182,   183,   184,    -1,    12,    13,    -1,    -1,    -1,    17,
     18,    19,    -1,    21,    22,    23,    24,    25,    26,    27,
     28,    -1,    30,    -1,    -1,    33,    34,    35,    36,    37,
     -1,    -1,    -1,    41,    42,    43,    -1,    45,    46,    47,
     48,    -1,    -1,   180,    52,    53,    54,    -1,    56,    -1,
     58,    -1,    60,    -1,    -1,    63,    -1,    -1,    -1,    67,
     68,    69,    70,    71,    72,    -1,    74,    75,    -1,    -1,
     78,    -1,    -1,    81,    82,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    91,    -1,    93,    94,    95,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   104,   105,    -1,   107,
    108,   109,   110,   111,   112,   113,   114,   115,   116,    82,
     83,    84,    85,    86,    87,    88,    89,    90,    91,    82,
     83,    84,    85,    86,    87,    88,    89,    90,    91,    -1,
     -1,    -1,   140,   141,   142,   143,   144,   145,   146,   147,
    148,   149,    -1,   116,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   159,    -1,   116,    82,    83,    84,    85,    86,    87,
     88,    89,    90,    91,    -1,    -1,    -1,    -1,   176,   177,
     -1,   179,     6,   181,   182,   183,   184,    -1,    12,    13,
     -1,    -1,    -1,    17,    18,    19,    -1,    21,    22,    23,
     24,    25,    26,    27,    28,    -1,    30,    -1,    -1,    33,
     34,    35,    36,    37,    -1,   178,    -1,    41,    42,    43,
     -1,    45,    46,    47,    48,    -1,    -1,    -1,    52,    53,
     54,    -1,    56,    -1,    58,    -1,    60,    -1,    -1,    63,
     -1,    -1,    -1,    67,    68,    69,    70,    71,    72,    -1,
     74,    75,    -1,    -1,    78,    -1,    -1,    81,    82,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,    93,
     94,    95,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    104,   105,    -1,   107,   108,   109,   110,   111,   112,   113,
    114,   115,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   140,   141,   142,   143,
    144,   145,   146,   147,   148,   149,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   176,   177,    -1,   179,     6,   181,   182,   183,
    184,    -1,    12,    13,    -1,    -1,    -1,    17,    18,    19,
     -1,    21,    22,    23,    24,    25,    26,    27,    28,    -1,
     30,    -1,    -1,    33,    34,    35,    36,    37,    -1,    -1,
     -1,    41,    42,    43,    -1,    45,    46,    47,    48,    -1,
     -1,    -1,    52,    53,    54,    -1,    56,    -1,    58,    -1,
     60,    -1,    -1,    63,    -1,    -1,    -1,    67,    68,    69,
     70,    71,    72,    -1,    74,    75,    -1,    -1,    78,    -1,
     -1,    81,    82,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    91,    -1,    93,    94,    95,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   104,   105,    -1,   107,   108,   109,
    110,   111,   112,   113,   114,   115,   116,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    140,   141,   142,   143,   144,   145,   146,   147,   148,   149,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   176,   177,    -1,   179,
      6,   181,   182,   183,   184,    -1,    12,    13,    -1,    -1,
     -1,    17,    18,    19,    -1,    21,    22,    23,    24,    25,
     26,    27,    28,    -1,    30,    -1,    -1,    33,    34,    35,
     36,    37,    -1,    -1,    -1,    41,    42,    43,    -1,    45,
     46,    47,    48,    -1,    -1,    -1,    52,    53,    54,    -1,
     56,    -1,    58,    -1,    60,    -1,    -1,    63,    -1,    -1,
     -1,    67,    68,    69,    70,    71,    72,    -1,    74,    75,
     -1,    -1,    78,    -1,    -1,    81,    82,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    91,    -1,    93,    94,    95,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   104,   105,
     -1,   107,   108,   109,   110,   111,   112,   113,   114,   115,
    116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,   145,
    146,   147,   148,   149,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    176,   177,    -1,   179,     6,   181,   182,   183,   184,    -1,
     12,    13,    -1,    -1,    -1,    17,    18,    19,    -1,    21,
     22,    23,    24,    25,    26,    27,    -1,    -1,    30,    -1,
     65,    33,    34,    35,    36,    37,    -1,    -1,    73,    41,
     42,    43,    -1,    45,    46,    47,    -1,    82,    83,    84,
     85,    86,    87,    88,    89,    90,    91,    92,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    67,    -1,    -1,    -1,    71,
     72,    -1,    -1,    -1,    -1,    -1,    78,    -1,    -1,    -1,
     82,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    91,
     -1,    -1,    94,    95,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   104,   105,    -1,   107,   108,   109,   110,   111,
    112,   113,   114,   115,   116,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,   141,
    142,   143,   144,   145,   146,   147,   148,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   177,    -1,     6,    -1,   181,
    182,   183,   184,    12,    13,    -1,    -1,    -1,    17,    18,
     19,    -1,    21,    22,    23,    24,    25,    26,    27,    -1,
     -1,    30,    -1,    -1,    33,    34,    35,    36,    37,    -1,
     -1,    -1,    41,    42,    43,    -1,    45,    46,    47,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    67,    -1,
     -1,    -1,    71,    72,    -1,    -1,    -1,    -1,    -1,    78,
     -1,    -1,    -1,    82,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    91,    -1,    -1,    94,    95,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   104,   105,    -1,   107,   108,
    109,   110,   111,   112,   113,   114,   115,   116,     5,    -1,
      7,     8,     9,    10,    11,    12,    13,    14,    15,    16,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   140,   141,   142,   143,   144,   145,   146,   147,   148,
     -1,    38,    39,    40,    -1,    -1,    -1,    44,    -1,    -1,
    159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   177,    -1,
     -1,    -1,   181,   182,   183,   184,     5,    -1,     7,     8,
      9,    10,    11,    12,    13,    14,    15,    16,     5,    -1,
      7,     8,     9,    10,    11,    12,    13,    14,    15,    16,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    38,
     39,    40,    -1,    -1,    -1,    44,    -1,    -1,    -1,    -1,
     -1,    38,    39,    40,    -1,    -1,    -1,    44,    -1,    -1,
     -1,    -1,   129,   130,   131,   132,   133,   134,   135,   136,
    137,   138,   139,     5,    -1,     7,     8,     9,    10,    11,
     12,    13,    14,    15,    16,     5,    -1,     7,     8,     9,
     10,    11,    12,    13,    14,    15,    16,    -1,    -1,   166,
    167,    -1,   169,   170,   171,    -1,    38,    39,    40,    -1,
     -1,    -1,    44,   180,    -1,    -1,    -1,    -1,    38,    39,
     40,    -1,    -1,    -1,    44,    -1,    -1,    -1,    -1,    -1,
    129,   130,   131,   132,   133,   134,   135,   136,   137,   138,
    139,    -1,   129,   130,   131,   132,   133,   134,   135,   136,
    137,   138,   139,     5,    -1,     7,     8,     9,    10,    11,
     12,    13,    14,    15,    16,    -1,    -1,   166,   167,    -1,
    169,   170,   171,    -1,    -1,    -1,    -1,    -1,    -1,   166,
    167,   180,   169,   170,   171,    -1,    38,    39,    40,    -1,
     -1,    -1,    44,   180,    -1,    -1,    -1,   129,   130,   131,
    132,   133,   134,   135,   136,   137,   138,   139,    -1,   129,
    130,   131,   132,   133,   134,   135,   136,   137,   138,   139,
      5,    -1,     7,     8,     9,    10,    11,    12,    13,    14,
     15,    16,    -1,    -1,   166,   167,    -1,   169,   170,   171,
     -1,    -1,    -1,    -1,    -1,    -1,   166,   167,   180,   169,
    170,   171,    -1,    38,    39,    40,    -1,    -1,    -1,    44,
    180,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   129,   130,   131,
    132,   133,   134,   135,   136,   137,   138,   139,    -1,     5,
     -1,     7,     8,     9,    10,    11,    12,    13,    14,    15,
     16,    -1,     5,    -1,     7,     8,     9,    10,    11,    12,
     13,    14,    15,    16,   166,   167,    -1,   169,   170,   171,
     -1,    -1,    38,    39,    40,    -1,    -1,   179,    44,    -1,
     -1,    -1,    -1,    -1,    -1,    38,    39,    40,    -1,    -1,
     -1,    44,    -1,    -1,   129,   130,   131,   132,   133,   134,
    135,   136,   137,   138,   139,    -1,    -1,     5,    -1,     7,
      8,     9,    10,    11,    12,    13,    14,    15,    16,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   166,   167,    -1,   169,   170,   171,    -1,    -1,   174,
     38,    39,    40,   178,    -1,    -1,    44,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1
    };
  }

  private static short[] yycheck_init_1()
  {
    return new short[]
    {
     -1,    -1,    -1,   129,   130,   131,   132,   133,   134,   135,
    136,   137,   138,   139,    -1,    -1,   129,   130,   131,   132,
    133,   134,   135,   136,   137,   138,   139,     5,    -1,     7,
      8,     9,    10,    11,    12,    13,    14,    15,    16,    -1,
    166,   167,    -1,   169,   170,   171,    -1,    -1,    -1,    -1,
     -1,    -1,   178,   166,   167,    -1,   169,   170,   171,    -1,
     38,    39,    40,    -1,    -1,   178,    44,    -1,    -1,    -1,
     -1,   129,   130,   131,   132,   133,   134,   135,   136,   137,
    138,   139,     5,    -1,     7,     8,     9,    10,    11,    12,
     13,    14,    15,    16,    -1,     5,    -1,     7,     8,     9,
     10,    11,    12,    13,    14,    15,    16,    -1,   166,   167,
     -1,   169,   170,   171,    -1,    38,    39,    40,    -1,    -1,
    178,    44,    -1,    -1,    -1,    -1,    -1,    -1,    38,    39,
     40,    -1,    -1,    -1,    44,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   129,   130,   131,   132,   133,   134,   135,   136,   137,
    138,   139,     5,    -1,     7,     8,     9,    10,    11,    12,
     13,    14,    15,    16,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   166,   167,
     -1,   169,   170,   171,    -1,    38,    39,    40,    -1,    -1,
    178,    44,    -1,    -1,    -1,    -1,   129,   130,   131,   132,
    133,   134,   135,   136,   137,   138,   139,    -1,    -1,   129,
    130,   131,   132,   133,   134,   135,   136,   137,   138,   139,
      5,    -1,     7,     8,     9,    10,    11,    12,    13,    14,
     15,    16,    -1,   166,   167,    -1,   169,   170,   171,    -1,
     -1,    -1,    -1,    -1,    -1,   178,   166,   167,    -1,   169,
    170,   171,    -1,    38,    39,    40,    -1,    -1,   178,    44,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   129,   130,   131,   132,
    133,   134,   135,   136,   137,   138,   139,     5,    -1,     7,
      8,     9,    10,    11,    12,    13,    14,    15,    16,    -1,
      5,    -1,     7,     8,     9,    10,    11,    12,    13,    14,
     15,    16,    -1,   166,   167,    -1,   169,   170,   171,    -1,
     38,    39,    40,    -1,    -1,   178,    44,    -1,    -1,    -1,
     -1,    -1,    -1,    38,    39,    40,    -1,    -1,    -1,    44,
     -1,    -1,    -1,    -1,   129,   130,   131,   132,   133,   134,
    135,   136,   137,   138,   139,     5,    -1,     7,     8,     9,
     10,    11,    12,    13,    14,    15,    16,    -1,     5,    -1,
      7,     8,     9,    10,    11,    12,    13,    14,    15,    16,
     -1,   166,   167,    -1,   169,   170,   171,    -1,    38,    39,
     40,    -1,    -1,   178,    44,    -1,    -1,    -1,    -1,    -1,
     -1,    38,    39,    40,    -1,    -1,    -1,    44,    -1,    -1,
     -1,   129,   130,   131,   132,   133,   134,   135,   136,   137,
    138,   139,    -1,    -1,   129,   130,   131,   132,   133,   134,
    135,   136,   137,   138,   139,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   166,   167,
     -1,   169,   170,   171,    -1,    -1,    -1,    -1,    -1,    -1,
    178,   166,   167,    -1,   169,   170,   171,    -1,    -1,    -1,
     -1,    -1,    -1,   178,    -1,    -1,    -1,    -1,    -1,   129,
    130,   131,   132,   133,   134,   135,   136,   137,   138,   139,
     -1,    -1,   129,   130,   131,   132,   133,   134,   135,   136,
    137,   138,   139,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   166,   167,    -1,   169,
    170,   171,    -1,    -1,    -1,    -1,    -1,    -1,   178,   166,
    167,    -1,   169,   170,   171,    -1,    -1,    12,    13,    -1,
     -1,   178,    17,    18,    19,    -1,    21,    22,    23,    24,
     25,    26,    27,    -1,    -1,    30,    -1,    -1,    33,    34,
     35,    36,    37,    38,    39,    40,    41,    42,    43,    44,
     45,    46,    47,    48,    49,    50,    51,    52,    53,    54,
     55,    56,    57,    58,    59,    60,    61,    62,    63,    64,
     65,    66,    67,    68,    69,    70,    71,    72,    73,    74,
     75,    76,    77,    78,    79,    80,    81,    82,    83,    84,
     85,    86,    87,    -1,    -1,    -1,    91,    92,    93,    94,
     95,    -1,    97,    98,    99,   100,   101,   102,   103,   104,
    105,   106,   107,   108,   109,   110,   111,   112,   113,   114,
    115,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,
    145,   146,   147,   148,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,
    165,    -1,     7,     8,     9,    10,    11,    12,    13,    14,
     15,    16,   177,   178,    -1,    -1,   181,   182,   183,   184,
     12,    13,    -1,    -1,    -1,    17,    18,    19,    -1,    21,
     22,    23,    24,    25,    26,    27,    -1,    -1,    30,    44,
     -1,    33,    34,    35,    36,    37,    38,    39,    40,    41,
     42,    43,    44,    45,    46,    47,    48,    49,    50,    51,
     52,    53,    54,    55,    56,    57,    58,    59,    60,    61,
     62,    63,    64,    65,    66,    67,    68,    69,    70,    71,
     72,    73,    74,    75,    76,    77,    78,    79,    80,    81,
     82,    83,    84,    85,    86,    87,    -1,    -1,    -1,    91,
     92,    93,    94,    95,    -1,    97,    98,    99,   100,   101,
    102,   103,   104,   105,   106,   107,   108,   109,   110,   111,
    112,   113,   114,   115,   116,    -1,   131,   132,   133,   134,
    135,   136,   137,   138,   139,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,   141,
    142,   143,   144,   145,   146,   147,   148,    -1,    -1,    -1,
     -1,    -1,   167,    -1,   169,   170,   171,   159,    -1,    -1,
     -1,    -1,    -1,   165,    -1,    -1,     8,     9,    10,    11,
     12,    13,    14,    15,    16,   177,   178,    -1,    -1,   181,
    182,   183,   184,    12,    13,    -1,    -1,    -1,    17,    18,
     19,    -1,    21,    22,    23,    24,    25,    26,    27,    -1,
     -1,    30,    44,    -1,    33,    34,    35,    36,    37,    38,
     39,    40,    41,    42,    43,    44,    45,    46,    47,    48,
     49,    50,    51,    52,    53,    54,    55,    56,    57,    58,
     59,    60,    61,    62,    63,    64,    65,    66,    67,    68,
     69,    70,    71,    72,    73,    74,    75,    76,    77,    78,
     79,    80,    81,    82,    83,    84,    85,    86,    87,    -1,
     -1,    -1,    91,    92,    93,    94,    95,    -1,    97,    98,
     99,   100,   101,   102,   103,   104,   105,   106,   107,   108,
    109,   110,   111,   112,   113,   114,   115,   116,    -1,   131,
    132,   133,   134,   135,   136,   137,   138,   139,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   140,   141,   142,   143,   144,   145,   146,   147,   148,
     -1,    -1,    -1,    -1,    -1,   167,    -1,   169,   170,   171,
    159,    -1,    -1,    -1,    -1,    -1,   165,    -1,    -1,    -1,
      9,    10,    11,    12,    13,    14,    15,    16,   177,   178,
     -1,    -1,   181,   182,   183,   184,    12,    13,    -1,    -1,
     -1,    17,    18,    19,    -1,    21,    22,    23,    24,    25,
     26,    27,    -1,    -1,    30,    44,    -1,    33,    34,    35,
     36,    37,    38,    39,    40,    41,    42,    43,    44,    45,
     46,    47,    48,    49,    50,    51,    52,    53,    54,    55,
     56,    57,    58,    59,    60,    61,    62,    63,    64,    65,
     66,    67,    68,    69,    70,    71,    72,    73,    74,    75,
     76,    77,    78,    79,    80,    81,    82,    83,    84,    85,
     86,    87,    -1,    -1,    -1,    91,    92,    93,    94,    95,
     -1,    97,    98,    99,   100,   101,   102,   103,   104,   105,
    106,   107,   108,   109,   110,   111,   112,   113,   114,   115,
    116,    -1,   131,   132,   133,   134,   135,   136,   137,   138,
    139,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,   145,
    146,   147,   148,    -1,    -1,    -1,    -1,    -1,   167,    -1,
    169,   170,   171,   159,    -1,    -1,    -1,    -1,    -1,   165,
     -1,    -1,    -1,     9,    10,    11,    12,    13,    14,    15,
     16,   177,    -1,    -1,    -1,   181,   182,   183,   184,    12,
     13,    -1,    -1,    -1,    17,    18,    19,    -1,    21,    22,
     23,    24,    25,    26,    27,    28,    -1,    30,    44,    -1,
     33,    34,    35,    36,    37,    -1,    -1,    -1,    41,    42,
     43,    -1,    45,    46,    47,    48,    -1,    -1,    -1,    52,
     53,    54,    -1,    56,    -1,    58,    -1,    60,    -1,    -1,
     63,    -1,    -1,    -1,    67,    68,    69,    70,    71,    72,
     73,    74,    75,    -1,    -1,    78,    79,    -1,    81,    82,
     83,    84,    -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,
     93,    94,    95,    96,    97,    98,    99,   100,    -1,    -1,
    103,   104,   105,    -1,   107,   108,   109,   110,   111,   112,
    113,   114,   115,   116,    -1,   131,   132,   133,   134,   135,
    136,   137,   138,   139,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,   141,   142,
    143,   144,   145,   146,   147,   148,   149,    -1,    -1,    -1,
     -1,   167,    -1,   169,    -1,    -1,   159,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   176,   177,    -1,   179,   180,   181,   182,
    183,   184,    12,    13,    -1,    -1,    -1,    17,    18,    19,
     -1,    21,    22,    23,    24,    25,    26,    27,    28,    -1,
     30,    -1,    -1,    33,    34,    35,    36,    37,    -1,    -1,
     -1,    41,    42,    43,    -1,    45,    46,    47,    48,    -1,
     -1,    -1,    52,    53,    54,    -1,    56,    -1,    58,    -1,
     60,    -1,    -1,    63,    -1,    -1,    -1,    67,    68,    69,
     70,    71,    72,    73,    74,    75,    -1,    -1,    78,    79,
     -1,    81,    82,    83,    84,    -1,    -1,    -1,    -1,    -1,
     -1,    91,    -1,    93,    94,    95,    96,    97,    98,    99,
    100,    -1,    -1,   103,   104,   105,    -1,   107,   108,   109,
    110,   111,   112,   113,   114,   115,   116,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    140,   141,   142,   143,   144,   145,   146,   147,   148,   149,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   176,   177,    -1,   179,
    180,   181,   182,   183,   184,    12,    13,    -1,    -1,    -1,
     17,    18,    19,    -1,    21,    22,    23,    24,    25,    26,
     27,    28,    -1,    30,    -1,    -1,    33,    34,    35,    36,
     37,    -1,    -1,    -1,    41,    42,    43,    -1,    45,    46,
     47,    48,    -1,    -1,    -1,    52,    53,    54,    -1,    56,
     -1,    58,    -1,    60,    -1,    -1,    63,    -1,    -1,    -1,
     67,    68,    69,    70,    71,    72,    73,    74,    75,    -1,
     -1,    78,    79,    -1,    81,    82,    83,    84,    -1,    -1,
     -1,    -1,    -1,    -1,    91,    -1,    93,    94,    95,    96,
     97,    98,    99,   100,    -1,    -1,   103,   104,   105,    -1,
    107,   108,   109,   110,   111,   112,   113,   114,   115,   116,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   140,   141,   142,   143,   144,   145,   146,
    147,   148,   149,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   176,
    177,    -1,   179,    -1,   181,   182,   183,   184,    12,    13,
     -1,    -1,    -1,    17,    18,    19,    -1,    21,    22,    23,
     24,    25,    26,    27,    28,    -1,    30,    -1,    -1,    33,
     34,    35,    36,    37,    -1,    -1,    -1,    41,    42,    43,
     -1,    45,    46,    47,    48,    -1,    -1,    -1,    52,    53,
     54,    -1,    56,    -1,    58,    -1,    60,    -1,    -1,    63,
     -1,    -1,    -1,    67,    68,    69,    70,    71,    72,    -1,
     74,    75,    -1,    -1,    78,    -1,    -1,    81,    82,    83,
     84,    -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,    93,
     94,    95,    96,    97,    98,    99,   100,    -1,    -1,    -1,
    104,   105,    -1,   107,   108,   109,   110,   111,   112,   113,
    114,   115,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   140,   141,   142,   143,
    144,   145,   146,   147,   148,   149,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   176,   177,    -1,   179,   180,   181,   182,   183,
    184,    12,    13,    -1,    -1,    -1,    17,    18,    19,    -1,
     21,    22,    23,    24,    25,    26,    27,    28,    -1,    30,
     -1,    -1,    33,    34,    35,    36,    37,    -1,    -1,    -1,
     41,    42,    43,    -1,    45,    46,    47,    48,    -1,    -1,
     -1,    52,    53,    54,    -1,    56,    -1,    58,    -1,    60,
     -1,    -1,    63,    -1,    -1,    -1,    67,    68,    69,    70,
     71,    72,    -1,    74,    75,    -1,    -1,    78,    -1,    -1,
     81,    82,    83,    84,    -1,    -1,    -1,    -1,    -1,    -1,
     91,    -1,    93,    94,    95,    96,    97,    98,    99,   100,
     -1,    -1,    -1,   104,   105,    -1,   107,   108,   109,   110,
    111,   112,   113,   114,   115,   116,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,
    141,   142,   143,   144,   145,   146,   147,   148,   149,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   176,   177,    -1,   179,   180,
    181,   182,   183,   184,    12,    13,    -1,    -1,    -1,    17,
     18,    19,    -1,    21,    22,    23,    24,    25,    26,    27,
     28,    -1,    30,    -1,    -1,    33,    34,    35,    36,    37,
     -1,    -1,    -1,    41,    42,    43,    -1,    45,    46,    47,
     48,    -1,    -1,    51,    52,    53,    54,    -1,    56,    -1,
     58,    -1,    60,    -1,    -1,    63,    -1,    -1,    -1,    67,
     68,    69,    70,    71,    72,    -1,    74,    75,    -1,    -1,
     78,    -1,    -1,    81,    82,    83,    84,    -1,    -1,    -1,
     -1,    -1,    -1,    91,    -1,    93,    94,    95,    96,    97,
     98,    99,   100,    -1,    -1,    -1,   104,   105,    -1,   107,
    108,   109,   110,   111,   112,   113,   114,   115,   116,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   140,   141,   142,   143,   144,   145,   146,   147,
    148,   149,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   176,   177,
     -1,   179,    -1,   181,   182,   183,   184,    12,    13,    -1,
     -1,    -1,    17,    18,    19,    -1,    21,    22,    23,    24,
     25,    26,    27,    28,    -1,    30,    -1,    -1,    33,    34,
     35,    36,    37,    -1,    -1,    -1,    41,    42,    43,    -1,
     45,    46,    47,    48,    -1,    -1,    -1,    52,    53,    54,
     55,    56,    -1,    58,    -1,    60,    -1,    -1,    63,    -1,
     -1,    -1,    67,    68,    69,    70,    71,    72,    -1,    74,
     75,    -1,    -1,    78,    -1,    -1,    81,    82,    83,    84,
     -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,    93,    94,
     95,    96,    97,    98,    99,   100,    -1,    -1,    -1,   104,
    105,    -1,   107,   108,   109,   110,   111,   112,   113,   114,
    115,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,
    145,   146,   147,   148,   149,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   176,   177,    -1,   179,    -1,   181,   182,   183,   184,
     12,    13,    -1,    -1,    -1,    17,    18,    19,    -1,    21,
     22,    23,    24,    25,    26,    27,    28,    -1,    30,    -1,
     -1,    33,    34,    35,    36,    37,    -1,    -1,    -1,    41,
     42,    43,    -1,    45,    46,    47,    48,    -1,    -1,    -1,
     52,    53,    54,    -1,    56,    -1,    58,    -1,    60,    61,
     -1,    63,    -1,    -1,    -1,    67,    68,    69,    70,    71,
     72,    -1,    74,    75,    -1,    -1,    78,    -1,    -1,    81,
     82,    83,    84,    -1,    -1,    -1,    -1,    -1,    -1,    91,
     -1,    93,    94,    95,    96,    97,    98,    99,   100,    -1,
     -1,    -1,   104,   105,    -1,   107,   108,   109,   110,   111,
    112,   113,   114,   115,   116,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,   141,
    142,   143,   144,   145,   146,   147,   148,   149,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   176,   177,    -1,   179,    -1,   181,
    182,   183,   184,    12,    13,    -1,    -1,    -1,    17,    18,
     19,    -1,    21,    22,    23,    24,    25,    26,    27,    28,
     -1,    30,    -1,    -1,    33,    34,    35,    36,    37,    -1,
     -1,    -1,    41,    42,    43,    -1,    45,    46,    47,    48,
     -1,    -1,    -1,    52,    53,    54,    -1,    56,    -1,    58,
     59,    60,    -1,    -1,    63,    -1,    -1,    -1,    67,    68,
     69,    70,    71,    72,    -1,    74,    75,    -1,    -1,    78,
     -1,    -1,    81,    82,    83,    84,    -1,    -1,    -1,    -1,
     -1,    -1,    91,    -1,    93,    94,    95,    96,    97,    98,
     99,   100,    -1,    -1,    -1,   104,   105,    -1,   107,   108,
    109,   110,   111,   112,   113,   114,   115,   116,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   140,   141,   142,   143,   144,   145,   146,   147,   148,
    149,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   176,   177,    -1,
    179,    -1,   181,   182,   183,   184,    12,    13,    -1,    -1,
     -1,    17,    18,    19,    -1,    21,    22,    23,    24,    25,
     26,    27,    28,    -1,    30,    -1,    -1,    33,    34,    35,
     36,    37,    -1,    -1,    -1,    41,    42,    43,    -1,    45,
     46,    47,    48,    -1,    -1,    -1,    52,    53,    54,    -1,
     56,    -1,    58,    -1,    60,    -1,    -1,    63,    -1,    -1,
     -1,    67,    68,    69,    70,    71,    72,    -1,    74,    75,
     -1,    -1,    78,    -1,    -1,    81,    82,    83,    84,    -1,
     -1,    -1,    -1,    -1,    -1,    91,    -1,    93,    94,    95,
     96,    97,    98,    99,   100,    -1,    -1,    -1,   104,   105,
     -1,   107,   108,   109,   110,   111,   112,   113,   114,   115,
    116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,   145,
    146,   147,   148,   149,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    176,   177,    -1,   179,   180,   181,   182,   183,   184,    12,
     13,    -1,    -1,    -1,    17,    18,    19,    -1,    21,    22,
     23,    24,    25,    26,    27,    28,    -1,    30,    -1,    -1,
     33,    34,    35,    36,    37,    -1,    -1,    -1,    41,    42
    };
  }

  private static short[] yycheck_init_2()
  {
    return new short[]
    {
     43,    -1,    45,    46,    47,    48,    -1,    -1,    -1,    52,
     53,    54,    -1,    56,    57,    58,    -1,    60,    -1,    -1,
     63,    -1,    -1,    -1,    67,    68,    69,    70,    71,    72,
     -1,    74,    75,    -1,    -1,    78,    -1,    -1,    81,    82,
     83,    84,    -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,
     93,    94,    95,    96,    97,    98,    99,   100,    -1,    -1,
     -1,   104,   105,    -1,   107,   108,   109,   110,   111,   112,
    113,   114,   115,   116,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,   141,   142,
    143,   144,   145,   146,   147,   148,   149,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   176,   177,    -1,   179,    -1,   181,   182,
    183,   184,    12,    13,    -1,    -1,    -1,    17,    18,    19,
     -1,    21,    22,    23,    24,    25,    26,    27,    28,    -1,
     30,    -1,    -1,    33,    34,    35,    36,    37,    -1,    -1,
     -1,    41,    42,    43,    -1,    45,    46,    47,    48,    -1,
     -1,    -1,    52,    53,    54,    -1,    56,    -1,    58,    -1,
     60,    -1,    -1,    63,    -1,    -1,    -1,    67,    68,    69,
     70,    71,    72,    -1,    74,    75,    -1,    -1,    78,    -1,
     -1,    81,    82,    83,    84,    -1,    -1,    -1,    -1,    -1,
     -1,    91,    -1,    93,    94,    95,    96,    97,    98,    99,
    100,    -1,    -1,    -1,   104,   105,    -1,   107,   108,   109,
    110,   111,   112,   113,   114,   115,   116,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    140,   141,   142,   143,   144,   145,   146,   147,   148,   149,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   176,   177,    -1,   179,
    180,   181,   182,   183,   184,    12,    13,    -1,    -1,    -1,
     17,    18,    19,    -1,    21,    22,    23,    24,    25,    26,
     27,    28,    -1,    30,    -1,    -1,    33,    34,    35,    36,
     37,    -1,    -1,    -1,    41,    42,    43,    -1,    45,    46,
     47,    48,    -1,    -1,    -1,    52,    53,    54,    -1,    56,
     -1,    58,    -1,    60,    -1,    -1,    63,    -1,    -1,    -1,
     67,    68,    69,    70,    71,    72,    -1,    74,    75,    -1,
     -1,    78,    -1,    -1,    81,    82,    83,    84,    -1,    -1,
     -1,    -1,    -1,    -1,    91,    -1,    93,    94,    95,    96,
     97,    98,    99,   100,    -1,    -1,    -1,   104,   105,    -1,
    107,   108,   109,   110,   111,   112,   113,   114,   115,   116,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   140,   141,   142,   143,   144,   145,   146,
    147,   148,   149,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   176,
    177,    -1,   179,   180,   181,   182,   183,   184,    12,    13,
     -1,    -1,    -1,    17,    18,    19,    -1,    21,    22,    23,
     24,    25,    26,    27,    28,    -1,    30,    -1,    -1,    33,
     34,    35,    36,    37,    -1,    -1,    -1,    41,    42,    43,
     -1,    45,    46,    47,    48,    -1,    -1,    -1,    52,    53,
     54,    -1,    56,    -1,    58,    -1,    60,    -1,    -1,    63,
     -1,    -1,    -1,    67,    68,    69,    70,    71,    72,    -1,
     74,    75,    -1,    -1,    78,    -1,    -1,    81,    82,    83,
     84,    -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,    93,
     94,    95,    96,    97,    98,    99,   100,    -1,    -1,    -1,
    104,   105,    -1,   107,   108,   109,   110,   111,   112,   113,
    114,   115,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   140,   141,   142,   143,
    144,   145,   146,   147,   148,   149,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   176,   177,    -1,   179,   180,   181,   182,   183,
    184,    12,    13,    -1,    -1,    -1,    17,    18,    19,    -1,
     21,    22,    23,    24,    25,    26,    27,    28,    -1,    30,
     -1,    -1,    33,    34,    35,    36,    37,    -1,    -1,    -1,
     41,    42,    43,    -1,    45,    46,    47,    48,    -1,    -1,
     -1,    52,    53,    54,    -1,    56,    -1,    58,    -1,    60,
     -1,    -1,    63,    -1,    -1,    -1,    67,    68,    69,    70,
     71,    72,    -1,    74,    75,    -1,    -1,    78,    -1,    -1,
     81,    82,    83,    84,    -1,    -1,    -1,    -1,    -1,    -1,
     91,    -1,    93,    94,    95,    96,    97,    98,    99,   100,
     -1,    -1,    -1,   104,   105,    -1,   107,   108,   109,   110,
    111,   112,   113,   114,   115,   116,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,
    141,   142,   143,   144,   145,   146,   147,   148,   149,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   176,   177,    -1,   179,   180,
    181,   182,   183,   184,    12,    13,    -1,    -1,    -1,    17,
     18,    19,    -1,    21,    22,    23,    24,    25,    26,    27,
     28,    -1,    30,    -1,    -1,    33,    34,    35,    36,    37,
     -1,    -1,    -1,    41,    42,    43,    -1,    45,    46,    47,
     48,    -1,    -1,    -1,    52,    53,    54,    -1,    56,    -1,
     58,    -1,    60,    -1,    -1,    63,    -1,    -1,    -1,    67,
     68,    69,    70,    71,    72,    -1,    74,    75,    -1,    -1,
     78,    -1,    -1,    81,    82,    83,    84,    -1,    -1,    -1,
     -1,    -1,    -1,    91,    -1,    93,    94,    95,    96,    97,
     98,    99,   100,    -1,    -1,    -1,   104,   105,    -1,   107,
    108,   109,   110,   111,   112,   113,   114,   115,   116,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   140,   141,   142,   143,   144,   145,   146,   147,
    148,   149,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   176,   177,
     -1,   179,   180,   181,   182,   183,   184,    12,    13,    -1,
     -1,    -1,    17,    18,    19,    -1,    21,    22,    23,    24,
     25,    26,    27,    28,    -1,    30,    -1,    -1,    33,    34,
     35,    36,    37,    -1,    -1,    -1,    41,    42,    43,    -1,
     45,    46,    47,    48,    -1,    -1,    -1,    52,    53,    54,
     -1,    56,    -1,    58,    -1,    60,    -1,    -1,    63,    -1,
     -1,    -1,    67,    68,    69,    70,    71,    72,    -1,    74,
     75,    -1,    -1,    78,    -1,    -1,    81,    82,    83,    84,
     -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,    93,    94,
     95,    96,    97,    98,    99,   100,    -1,    -1,    -1,   104,
    105,    -1,   107,   108,   109,   110,   111,   112,   113,   114,
    115,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,
    145,   146,   147,   148,   149,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,     5,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   176,   177,    -1,   179,    -1,   181,   182,   183,   184,
     23,    24,    25,    26,    -1,    -1,    -1,    -1,    -1,    -1,
     33,    34,    35,    36,    37,    38,    39,    40,    41,    42,
     -1,    44,    45,    46,    47,    48,    49,    50,    51,    52,
     53,    54,    55,    56,    57,    58,    59,    60,    61,    62,
     63,    64,    65,    66,    67,    68,    69,    70,    71,    72,
     73,    74,    75,    76,    77,    78,    79,    80,    81,    82,
     83,    84,    85,    86,    87,    -1,    -1,    -1,    91,    92,
     93,    94,    95,    -1,    97,    98,    99,   100,   101,   102,
    103,   104,   105,   106,   107,   108,   109,   110,   111,   112,
    113,   114,   115,    12,    13,    -1,    -1,    -1,    17,    18,
     19,    -1,    21,    22,    23,    24,    25,    26,    27,    28,
     -1,    30,    -1,    -1,    33,    34,    35,    36,    37,    -1,
     -1,    -1,    41,    42,    43,    -1,    45,    46,    47,    48,
     -1,    -1,    -1,    52,    53,    54,    -1,    56,    -1,    58,
     -1,    60,    -1,    -1,    63,    -1,    -1,    -1,    67,    68,
     69,    70,    71,    72,   177,    74,    75,    -1,    -1,    78,
     -1,    -1,    81,    82,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    91,    -1,    93,    94,    95,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   104,   105,    -1,   107,   108,
    109,   110,   111,   112,   113,   114,   115,   116,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   140,   141,   142,   143,   144,   145,   146,   147,   148,
    149,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   176,   177,    -1,
    179,    -1,   181,   182,   183,   184,    12,    13,    -1,    -1,
     -1,    17,    18,    19,    -1,    21,    22,    23,    24,    25,
     26,    27,    -1,    -1,    30,    -1,    -1,    33,    34,    35,
     36,    37,    -1,    -1,    -1,    41,    42,    43,    -1,    45,
     46,    47,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    67,    -1,    -1,    -1,    71,    72,    -1,    -1,    -1,
     -1,    -1,    78,    -1,    -1,    -1,    82,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    91,    -1,    -1,    94,    95,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   104,   105,
     -1,   107,   108,   109,   110,   111,   112,   113,   114,   115,
    116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,   145,
    146,   147,   148,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,   165,
     -1,    -1,    -1,    -1,   170,   171,    -1,    -1,    -1,    -1,
     -1,   177,    -1,    -1,    -1,   181,   182,   183,   184,    12,
     13,    -1,    -1,    -1,    17,    18,    19,    -1,    21,    22,
     23,    24,    25,    26,    27,    -1,    -1,    30,    -1,    -1,
     33,    34,    35,    36,    37,    -1,    -1,    -1,    41,    42,
     43,    -1,    45,    46,    47,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    67,    -1,    -1,    -1,    71,    72,
     -1,    -1,    -1,    -1,    -1,    78,    -1,    -1,    -1,    82,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,
     -1,    94,    95,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   104,   105,    -1,   107,   108,   109,   110,   111,   112,
    113,   114,   115,   116,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,   141,   142,
    143,   144,   145,   146,   147,   148,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   170,   171,    -1,
     -1,    -1,    -1,    -1,   177,    -1,    -1,    -1,   181,   182,
    183,   184,    12,    13,    -1,    -1,    -1,    17,    18,    19,
     -1,    21,    22,    23,    24,    25,    26,    27,    -1,    -1,
     30,    -1,    -1,    33,    34,    35,    36,    37,    -1,    -1,
     -1,    41,    42,    43,    -1,    45,    46,    47,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    67,    -1,    -1,
     -1,    71,    72,    -1,    -1,    -1,    -1,    -1,    78,    -1,
     -1,    -1,    82,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    91,    -1,    -1,    94,    95,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   104,   105,    -1,   107,   108,   109,
    110,   111,   112,   113,   114,   115,   116,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    140,   141,   142,   143,   144,   145,   146,   147,   148,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    170,   171,    -1,    -1,    -1,    -1,    -1,   177,    -1,    -1,
     -1,   181,   182,   183,   184,    12,    13,    -1,    -1,    -1,
     17,    18,    19,    -1,    21,    22,    23,    24,    25,    26,
     27,    -1,    -1,    30,    -1,    -1,    33,    34,    35,    36,
     37,    -1,    -1,    -1,    41,    42,    43,    -1,    45,    46,
     47,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     67,    -1,    -1,    -1,    71,    72,    -1,    -1,    -1,    -1,
     -1,    78,    -1,    -1,    -1,    82,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    91,    -1,    -1,    94,    95,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   104,   105,    -1,
    107,   108,   109,   110,   111,   112,   113,   114,   115,   116,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   140,   141,   142,   143,   144,   145,   146,
    147,   148,   149,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    177,    -1,    -1,    -1,   181,   182,   183,   184,    12,    13,
     -1,    -1,    -1,    17,    18,    19,    -1,    21,    22,    23,
     24,    25,    26,    27,    -1,    -1,    30,    31,    -1,    33,
     34,    35,    36,    37,    -1,    -1,    -1,    41,    42,    43,
     -1,    45,    46,    47,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    67,    -1,    -1,    -1,    71,    72,    -1,
     -1,    -1,    -1,    -1,    78,    -1,    -1,    -1,    82,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,    -1,
     94,    95,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    104,   105,    -1,   107,   108,   109,   110,   111,   112,   113,
    114,   115,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   140,   141,   142,   143,
    144,   145,   146,   147,   148,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,     9,    10,    11,    12,    13,
     14,    15,    16,   177,    -1,    -1,    -1,   181,   182,   183,
    184,    12,    13,    -1,    -1,    -1,    17,    18,    19,    -1,
     21,    22,    23,    24,    25,    26,    27,    -1,    -1,    30,
     44,    -1,    33,    34,    35,    36,    37,    -1,    -1,    -1,
     41,    42,    43,    -1,    45,    46,    47,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    67,    -1,    -1,    -1,
     71,    72,    -1,    -1,    -1,    -1,    -1,    78,    -1,    -1,
     -1,    82,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     91,    -1,    -1,    94,    95,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   104,   105,    -1,   107,   108,   109,   110,
    111,   112,   113,   114,   115,   116,    -1,   131,   132,   133,
    134,   135,   136,   137,   138,   139,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,
    141,   142,   143,   144,   145,   146,   147,   148,    -1,    -1,
     -1,    -1,    -1,   167,    -1,   169,    -1,    -1,   159,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   177,   178,    -1,    -1,
    181,   182,   183,   184,    12,    13,    -1,    -1,    -1,    17,
     18,    19,    -1,    21,    22,    23,    24,    25,    26,    27,
     -1,    -1,    30,    -1,    -1,    33,    34,    35,    36,    37,
     -1,    -1,    -1,    41,    42,    43,    -1,    45,    46,    47,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    67,
     -1,    -1,    -1,    71,    72,    -1,    -1,    -1,    -1,    -1,
     78,    -1,    -1,    -1,    82,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    91,    -1,    -1,    94,    95,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,   104,   105,    -1,   107,
    108,   109,   110,   111,   112,   113,   114,   115,   116,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   140,   141,   142,   143,   144,   145,   146,   147,
    148,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   177,
    178,    -1,    -1,   181,   182,   183,   184,    12,    13,    -1,
     -1,    -1,    17,    18,    19,    -1,    21,    22,    23,    24,
     25,    26,    27,    -1,    -1,    30,    -1,    -1,    33,    34,
     35,    36,    37,    -1,    -1,    -1,    41,    42,    43,    -1,
     45,    46,    47,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    67,    -1,    -1,    -1,    71,    72,    -1,    -1,
     -1,    -1,    -1,    78,    -1,    -1,    -1,    82,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,    -1,    94,
     95,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   104,
    105,    -1,   107,   108,   109,   110,   111,   112,   113,   114,
    115,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,
    145,   146,   147,   148,   149,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   177,    -1,    -1,    -1,   181,   182,   183,   184,
     12,    13,    -1,    -1,    -1,    17,    18,    19,    -1,    21,
     22,    23,    24,    25,    26,    27,    -1,    -1,    30,    -1,
     -1,    33,    34,    35,    36,    37,    -1,    -1,    -1,    41,
     42,    43,    -1,    45,    46,    47,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    66,    67,    -1,    -1,    -1,    71,
     72,    -1,    -1,    -1,    -1,    -1,    78,    -1,    -1,    -1,
     82,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    91,
     -1,    -1,    94,    95,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   104,   105,    -1,   107,   108,   109,   110,   111,
    112,   113,   114,   115,   116,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   140,   141,
    142,   143,   144,   145,   146,   147,   148,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,   159,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   177,    -1,    -1,    -1,   181
    };
  }

  private static short[] yycheck_init_3()
  {
    return new short[]
    {
    182,   183,   184,    12,    13,    -1,    -1,    -1,    17,    18,
     19,    -1,    21,    22,    23,    24,    25,    26,    27,    -1,
     -1,    30,    -1,    -1,    33,    34,    35,    36,    37,    -1,
     -1,    -1,    41,    42,    43,    -1,    45,    46,    47,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    67,    -1,
     -1,    -1,    71,    72,    -1,    -1,    -1,    -1,    -1,    78,
     -1,    -1,    -1,    82,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    91,    -1,    -1,    94,    95,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   104,   105,    -1,   107,   108,
    109,   110,   111,   112,   113,   114,   115,   116,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   140,   141,   142,   143,   144,   145,   146,   147,   148,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    159,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   177,    -1,
     -1,    -1,   181,   182,   183,   184,    12,    13,    -1,    -1,
     -1,    17,    18,    19,    -1,    21,    22,    23,    24,    25,
     26,    27,    -1,    -1,    30,    -1,    -1,    33,    34,    35,
     36,    37,    -1,    -1,    -1,    41,    42,    43,    -1,    45,
     46,    47,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    67,    -1,    -1,    -1,    71,    72,    -1,    -1,    -1,
     -1,    -1,    78,    -1,    -1,    -1,    82,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    91,    -1,    -1,    94,    95,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   104,   105,
     -1,   107,   108,   109,   110,   111,   112,   113,   114,   115,
    116,     5,     6,     7,     8,     9,    10,    11,    12,    13,
     14,    15,    16,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,   140,   141,   142,   143,   144,   145,
    146,   147,   148,    -1,    38,    39,    40,    -1,    -1,    -1,
     44,    -1,    -1,   159,    -1,    -1,    -1,    -1,    -1,     5,
     -1,     7,     8,     9,    10,    11,    12,    13,    14,    15,
     16,   177,    -1,    -1,    -1,   181,   182,   183,   184,     5,
     -1,     7,     8,     9,    10,    11,    12,    13,    14,    15,
     16,    -1,    38,    39,    40,    -1,    -1,    -1,    44,    -1,
     -1,    -1,    65,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     73,    -1,    38,    39,    40,    -1,    79,    -1,    44,    82,
     83,    84,    85,    86,    87,    88,    89,    90,    91,    92,
     -1,    -1,    -1,    -1,    -1,   129,   130,   131,   132,   133,
    134,   135,   136,   137,   138,   139,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   116,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   166,   167,    -1,   169,   170,   171,    -1,    -1,
     -1,    -1,   176,   129,   130,   131,   132,   133,   134,   135,
    136,   137,   138,   139,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   129,   130,   131,   132,   133,   134,   135,
    136,   137,   138,   139,    -1,    -1,    -1,   180,    -1,    -1,
    166,   167,    -1,   169,   170,   171,    -1,    -1,    -1,    -1,
    176,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    166,   167,    -1,   169,   170,   171,    -1,    -1,    -1,     5,
    176,     7,     8,     9,    10,    11,    12,    13,    14,    15,
     16,     5,    -1,     7,     8,     9,    10,    11,    12,    13,
     14,    15,    16,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    38,    39,    40,    -1,    -1,    -1,    44,    -1,
     -1,    -1,    -1,    -1,    38,    39,    40,    -1,    -1,    -1,
     44,    -1,    -1,    -1,    -1,    -1,    -1,    -1,     5,    -1,
      7,     8,     9,    10,    11,    12,    13,    14,    15,    16,
      5,    -1,     7,     8,     9,    10,    11,    12,    13,    14,
     15,    16,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    38,    39,    40,    -1,    -1,    -1,    44,    -1,    -1,
     -1,    -1,    -1,    38,    39,    40,    -1,    -1,    -1,    44,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   129,   130,   131,   132,   133,   134,   135,
    136,   137,   138,   139,    -1,   129,   130,   131,   132,   133,
    134,   135,   136,   137,   138,   139,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
    166,   167,    -1,   169,   170,   171,    -1,    -1,    -1,    -1,
    176,    -1,   166,   167,    -1,   169,   170,   171,    -1,    -1,
     -1,   175,   129,   130,   131,   132,   133,   134,   135,   136,
    137,   138,   139,    -1,   129,   130,   131,   132,   133,   134,
    135,   136,   137,   138,   139,    -1,    -1,     5,    -1,     7,
      8,     9,    10,    11,    12,    13,    14,    15,    16,   166,
    167,    -1,   169,   170,   171,    -1,    -1,   174,    -1,    -1,
     -1,   166,   167,    -1,   169,   170,   171,    -1,    -1,   174,
     38,    39,    40,    -1,    -1,    -1,    44,    -1,    -1,    -1,
      5,    -1,     7,     8,     9,    10,    11,    12,    13,    14,
     15,    16,     5,     6,     7,     8,     9,    10,    11,    12,
     13,    14,    15,    16,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    38,    39,    40,    -1,    -1,    -1,    44,
     -1,    -1,    -1,    -1,    -1,    38,    39,    40,    -1,    -1,
     -1,    44,    -1,    -1,    -1,    -1,    -1,    62,    -1,     5,
     -1,     7,     8,     9,    10,    11,    12,    13,    14,    15,
     16,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   129,   130,   131,   132,   133,   134,   135,   136,   137,
    138,   139,    38,    39,    40,    -1,    -1,    -1,    44,    -1,
     -1,    -1,    -1,    -1,   152,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,   166,   167,
     -1,   169,   170,   171,   129,   130,   131,   132,   133,   134,
    135,   136,   137,   138,   139,    -1,   129,   130,   131,   132,
    133,   134,   135,   136,   137,   138,   139,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,   166,   167,    -1,   169,   170,   171,    -1,    -1,    -1,
     -1,    -1,    -1,   166,   167,    -1,   169,   170,   171,    -1,
     -1,    -1,    -1,   129,   130,   131,   132,   133,   134,   135,
    136,   137,   138,   139,     5,    -1,     7,     8,     9,    10,
     11,    12,    13,    14,    15,    16,     5,    -1,     7,     8,
      9,    10,    11,    12,    13,    14,    15,    16,    -1,    -1,
    166,   167,    -1,   169,   170,   171,    -1,    -1,    39,    40,
     -1,    -1,    -1,    44,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    44,    -1,    -1,    -1,     5,
     -1,     7,     8,     9,    10,    11,    12,    13,    14,    15,
     16,     5,    -1,     7,     8,     9,    10,    11,    12,    13,
     14,    15,    16,    -1,     7,     8,     9,    10,    11,    12,
     13,    14,    15,    16,    40,    -1,    -1,    -1,    44,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     44,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    44,    -1,    -1,    -1,    -1,    -1,    -1,   129,   130,
    131,   132,   133,   134,   135,   136,   137,   138,   139,    -1,
    129,   130,   131,   132,   133,   134,   135,   136,   137,   138,
    139,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   152,    -1,   166,   167,    -1,   169,   170,
    171,    -1,    -1,    -1,    -1,    -1,    -1,   166,   167,    -1,
    169,   170,   171,   129,   130,   131,   132,   133,   134,   135,
    136,   137,   138,   139,    -1,   129,   130,   131,   132,   133,
    134,   135,   136,   137,   138,   139,   129,   130,   131,   132,
    133,   134,   135,   136,   137,   138,   139,    -1,    -1,    -1,
    166,   167,    -1,   169,   170,   171,    -1,    -1,    -1,    -1,
     -1,    -1,   166,   167,    -1,   169,   170,   171,    -1,    -1,
     -1,    23,    -1,   166,   167,    27,   169,   170,   171,    -1,
     -1,    33,    34,    35,    36,    37,    38,    39,    40,    41,
     42,    -1,    44,    45,    46,    47,    48,    49,    50,    51,
     52,    53,    54,    55,    56,    57,    58,    59,    60,    61,
     62,    63,    64,    65,    66,    67,    68,    69,    70,    71,
     72,    73,    74,    75,    76,    77,    78,    79,    80,    81,
     82,    83,    84,    85,    86,    87,    -1,    -1,    -1,    91,
     92,    93,    94,    95,    -1,    97,    98,    99,   100,   101,
    102,   103,   104,   105,   106,   107,   108,   109,   110,   111,
    112,   113,   114,   115,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    65,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    73,    -1,    -1,    -1,    -1,    -1,    79,    -1,
     -1,    82,    83,    84,    85,    86,    87,    88,    89,    90,
     91,    92,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    23,   116,    -1,   179,    27,    -1,
     -1,    -1,   184,    -1,    33,    34,    35,    36,    37,    38,
     39,    40,    41,    42,    -1,    44,    45,    46,    47,    48,
     49,    50,    51,    52,    53,    54,    55,    56,    57,    58,
     59,    60,    61,    62,    63,    64,    65,    66,    67,    68,
     69,    70,    71,    72,    73,    74,    75,    76,    77,    78,
     79,    80,    81,    82,    83,    84,    85,    86,    87,   180,
     -1,    -1,    91,    92,    93,    94,    95,    -1,    97,    98,
     99,   100,   101,   102,   103,   104,   105,   106,   107,   108,
    109,   110,   111,   112,   113,   114,   115,    -1,    23,    24,
     25,    26,    27,    -1,    -1,    30,    -1,    -1,    -1,    23,
     24,    25,    26,    27,    -1,    -1,    30,    -1,    65,    -1,
     45,    -1,    -1,    -1,    -1,    -1,    73,    -1,    -1,    -1,
     -1,    45,    79,    -1,    -1,    82,    83,    84,    85,    86,
     87,    88,    89,    90,    91,    92,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    82,    -1,    -1,
    179,    -1,    -1,    -1,    -1,   184,    91,    -1,    82,   116,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    91,    -1,   104,
    105,    -1,   107,   108,   109,   110,   111,   112,   113,   114,
    115,   105,    -1,   107,   108,   109,   110,   111,   112,   113,
    114,   115,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,   180,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   170,   171,    -1,    -1,    -1,
     -1,    -1,   177,    -1,    -1,    -1,   181,    -1,   183,   184,
     -1,    -1,    -1,   177,    -1,    -1,    -1,   181,    -1,   183,
    184,    23,    24,    25,    26,    -1,    -1,    -1,    -1,    -1,
     -1,    33,    34,    35,    36,    37,    38,    39,    40,    41,
     42,    -1,    44,    45,    46,    47,    48,    49,    50,    51,
     52,    53,    54,    55,    56,    57,    58,    59,    60,    61,
     62,    63,    64,    65,    66,    67,    68,    69,    70,    71,
     72,    73,    74,    75,    76,    77,    78,    79,    80,    81,
     82,    83,    84,    85,    86,    87,    -1,    -1,    -1,    91,
     92,    93,    94,    95,    -1,    97,    98,    99,   100,   101,
    102,   103,   104,   105,   106,   107,   108,   109,   110,   111,
    112,   113,   114,   115,    -1,    -1,    65,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    73,    -1,    -1,    -1,    -1,    -1,
     79,    -1,    -1,    82,    83,    84,    85,    86,    87,    88,
     89,    90,    91,    92,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    23,    24,
     25,    26,    -1,    -1,    -1,    -1,    -1,   116,    33,    34,
     35,    36,    37,    38,    39,    40,    41,    42,   180,    44,
     45,    46,    47,    48,    49,    50,    51,    52,    53,    54,
     55,    56,    57,    58,    59,    60,    61,    62,    63,    64,
     65,    66,    67,    68,    69,    70,    71,    72,    73,    74,
     75,    76,    77,    78,    79,    80,    81,    82,    83,    84,
     85,    86,    87,    -1,    -1,    -1,    91,    92,    93,    94,
     95,   180,    97,    98,    99,   100,   101,   102,   103,   104,
    105,   106,   107,   108,   109,   110,   111,   112,   113,   114,
    115,    65,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    73,
     -1,    -1,    -1,    -1,    -1,    79,    -1,    -1,    82,    83,
     84,    85,    86,    87,    88,    89,    90,    91,    92,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,   116,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,   180,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    23,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    -1,    33,    34,    35,    36,
     37,    38,    39,    40,    41,    42,   180,    44,    45,    46,
     47,    48,    49,    50,    51,    52,    53,    54,    55,    56,
     57,    58,    59,    60,    61,    62,    63,    64,    65,    66,
     67,    68,    69,    70,    71,    72,    73,    74,    75,    76,
     77,    78,    79,    80,    81,    82,    83,    84,    85,    86,
     87,    88,    89,    90,    91,    92,    93,    94,    95,    -1,
     97,    98,    99,   100,   101,   102,   103,   104,   105,   106,
    107,   108,   109,   110,   111,   112,   113,   114,   115,    23,
     -1,    -1,    26,    -1,    -1,    -1,    -1,    -1,    -1,    33,
     34,    35,    36,    37,    38,    39,    40,    41,    42,    -1,
     44,    45,    46,    47,    48,    49,    50,    51,    52,    53,
     54,    55,    56,    57,    58,    59,    60,    61,    62,    63,
     64,    65,    66,    67,    68,    69,    70,    71,    72,    73,
     74,    75,    76,    77,    78,    79,    80,    81,    82,    83,
     84,    85,    86,    87,    -1,    -1,    -1,    91,    92,    93,
     94,    95,    -1,    97,    98,    99,   100,   101,   102,   103,
    104,   105,   106,   107,   108,   109,   110,   111,   112,   113,
    114,   115,    23,    -1,    -1,    -1,    -1,    -1,    -1,    -1,
     -1,    -1,    33,    34,    35,    36,    37,    38,    39,    40,
     41,    42,    -1,    44,    45,    46,    47,    48,    49,    50,
     51,    52,    53,    54,    55,    56,    57,    58,    59,    60,
     61,    62,    63,    64,    65,    66,    67,    68,    69,    70,
     71,    72,    73,    74,    75,    76,    77,    78,    79,    80,
     81,    82,    83,    84,    85,    86,    87,    -1,    -1,    -1,
     91,    92,    93,    94,    95,    -1,    97,    98,    99,   100,
    101,   102,   103,   104,   105,   106,   107,   108,   109,   110,
    111,   112,   113,   114,   115,    23,    -1,    -1,    -1,    -1,
     -1,    -1,    -1,    -1,    -1,    33,    34,    35,    36,    37,
     38,    39,    40,    41,    42,    -1,    44,    45,    46,    47,
     48,    49,    50,    51,    52,    53,    54,    55,    56,    57,
     58,    59,    60,    61,    62,    63,    64,    65,    66,    67,
     68,    69,    70,    71,    72,    73,    74,    75,    76,    77,
     78,    79,    80,    81,    82,    83,    84,    85,    86,    87,
     -1,    -1,    -1,    91,    92,    93,    94,    95,    -1,    97,
     98,    99,   100,   101,   102,   103,   104,   105,   106,   107,
    108,   109,   110,   111,   112,   113,   114,   115
    };
  }

/* YYSTOS[STATE-NUM] -- The symbol kind of the accessing symbol of
   state STATE-NUM.  */
  private static final short[] yystos_ = yystos_init();
  private static final short[] yystos_init()
  {
    return new short[]
    {
       0,   186,   191,     0,    12,    13,    17,    18,    19,    21,
      22,    23,    24,    25,    26,    27,    28,    30,    33,    34,
      35,    36,    37,    41,    42,    43,    45,    46,    47,    48,
      52,    53,    54,    56,    58,    60,    63,    67,    68,    69,
      70,    71,    72,    73,    74,    75,    78,    79,    81,    82,
      83,    84,    91,    93,    94,    95,    96,    97,    98,    99,
     100,   103,   104,   105,   107,   108,   109,   110,   111,   112,
     113,   114,   115,   116,   140,   141,   142,   143,   144,   145,
     146,   147,   148,   149,   159,   176,   177,   179,   181,   182,
     183,   184,   195,   198,   199,   200,   201,   202,   218,   227,
     230,   233,   236,   237,   239,   241,   255,   261,   262,   263,
     264,   329,   330,   331,   332,   333,   334,   342,   344,   348,
     349,   350,   351,   353,   354,   355,   356,   357,   358,   359,
     360,   371,    23,    82,    91,   199,   331,   334,   331,   331,
     331,   331,     6,   331,   331,   177,   331,   331,   331,   331,
     331,    82,    91,   177,   195,   199,   234,   235,   236,   327,
     344,   345,   359,   361,   177,   284,   331,   177,   282,   347,
     177,   322,   323,   331,   218,   177,   177,   177,   177,   177,
     177,   331,   352,   352,    23,    23,   215,   321,   352,   179,
     331,    23,    24,    26,    71,    73,   193,   194,   205,   207,
     211,   214,   288,   289,   359,    27,   290,   291,   332,   282,
     177,   177,   177,   177,   232,   238,   240,   242,    23,    26,
      33,    34,    35,    36,    37,    38,    39,    40,    41,    42,
      44,    45,    46,    47,    48,    49,    50,    51,    52,    53,
      54,    55,    56,    57,    58,    59,    60,    61,    62,    63,
      64,    65,    66,    67,    68,    69,    70,    71,    72,    73,
      74,    75,    76,    77,    78,    79,    80,    81,    82,    83,
      84,    85,    86,    87,    91,    92,    93,    94,    95,    97,
      98,    99,   100,   101,   102,   103,   104,   105,   106,   107,
     108,   109,   110,   111,   112,   113,   114,   115,   187,   188,
     190,   192,   204,   177,   177,   335,    45,   177,   181,   329,
     348,   350,   351,   358,   358,   331,   331,   331,   331,   331,
     331,   331,   331,    27,    29,   160,   161,   162,   368,   369,
     331,   216,   104,   165,   170,   171,   189,   331,   364,   365,
     366,   367,    29,   346,   368,    29,   368,   179,   359,   282,
      82,   198,   201,   332,    97,   236,    49,    50,    49,    50,
      51,     5,     7,     8,     9,    10,    11,    12,    13,    14,
      15,    16,    38,    39,    40,    44,   129,   130,   131,   132,
     133,   134,   135,   136,   137,   138,   139,   166,   167,   169,
     170,   171,   176,   189,   338,   338,   163,   163,   150,   151,
     181,   343,     4,   117,   118,   119,   120,   121,   122,   123,
     124,   125,   126,   127,   128,   140,   141,   168,   338,   331,
     152,   331,   327,   236,    97,   163,   282,   150,   151,   163,
     181,    23,    33,    34,    35,    36,    37,    41,    42,    45,
      46,    47,    67,    71,    72,    78,    82,    91,    94,    95,
     104,   105,   107,   108,   109,   110,   111,   112,   113,   114,
     115,   165,   178,   190,   285,   286,   331,   165,   178,   283,
     286,   287,   331,   331,   174,   176,    54,   331,   149,   325,
     326,   331,   331,   215,   331,   331,   176,   176,   176,     4,
     174,   176,   176,   216,    62,   164,   194,   206,   211,   176,
     174,   176,   174,   176,     4,   174,   176,   224,   225,   358,
     331,   372,   373,   331,   178,    23,    23,    23,    23,   176,
     203,   179,   364,   364,   196,   197,   344,   345,   331,   364,
     176,   150,   151,   181,   160,   369,    31,   331,   358,    29,
     160,   369,   178,    96,   180,   199,   200,   217,   218,   177,
     331,   358,   152,   175,   174,   182,   183,   331,   332,   231,
     177,   218,   177,     6,   176,     6,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     345,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   335,    23,    91,
     226,   335,   179,   190,   359,   362,   179,   190,   359,   362,
      23,   179,   359,   363,   363,   352,   282,   189,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   178,   331,   178,   328,   359,   363,   363,   359,
     352,   178,   331,     6,   174,   208,   174,   178,   174,   208,
     178,   323,   177,   178,   331,   176,   174,    62,   178,   178,
     178,   331,   321,   180,    23,   179,   164,   176,   176,   194,
     214,   289,   331,   291,   174,   208,   174,   208,   178,   176,
     101,   246,   335,   101,   247,     6,   243,   179,   191,   178,
     178,   174,   208,   282,   178,   175,    23,    23,    13,    23,
      27,    32,   370,   180,   181,   180,   180,   177,   200,   364,
     104,   189,   331,     4,   365,   180,    23,   331,   331,   216,
     331,     6,   177,   335,   177,   331,   282,   331,   282,   331,
     282,   282,   175,   358,   347,   175,   331,   165,   287,   178,
     178,   287,   287,   178,     6,   218,   331,     6,   218,   260,
     324,   326,   331,   149,   331,   104,   181,   189,   249,   358,
     219,     6,   179,   253,   179,   335,   220,   193,   205,   209,
     212,   213,   179,   225,   178,   373,   178,   344,   102,   248,
     179,   295,   344,   335,     5,    82,   105,   106,   177,   195,
     271,   272,   273,   274,   275,   277,   248,   191,   180,     4,
     196,   175,    32,   175,   331,   178,   178,   177,   358,   331,
     246,   178,   178,    51,   331,    82,    83,    84,    85,    86,
      87,    88,    89,    90,    91,   199,   265,   266,   267,   268,
     269,   307,   308,   177,   265,   180,   180,   180,   246,   216,
     178,   216,   176,   174,   331,   177,   364,   358,   152,   178,
       6,   218,   252,   176,   254,   176,   254,    66,   256,   257,
     258,   259,   331,    76,    77,   223,    62,   213,   174,   208,
     210,   213,   176,   295,   335,   292,   174,   179,   272,   272,
     275,   171,     7,     7,   171,   335,   180,   331,   175,   176,
     364,   248,   218,     6,   176,   269,   178,   174,   208,     5,
     177,   270,   276,   277,   278,   279,   280,   308,   265,   178,
     248,   176,    55,   325,   331,   364,   175,   249,     6,   218,
     251,   216,   254,    64,    65,    66,   254,   180,   174,   208,
     180,   174,   208,   174,   208,   177,   179,    23,   212,   180,
     174,   208,   179,    65,    79,    92,   180,   199,   244,   293,
     294,   304,   305,   306,   307,   344,   292,   178,   272,   272,
     273,   273,   272,   179,   180,   178,   335,   216,     6,   281,
     267,   277,   277,   280,   170,   228,   171,     7,     7,   171,
     178,    79,   339,   335,   176,   178,   178,   178,   216,    61,
      64,   176,   331,     6,   176,   180,   152,   258,   331,   152,
     221,   344,   216,   213,   180,   292,   335,   295,   293,   270,
     334,    73,   180,   292,   179,   271,   152,   178,   165,   229,
     277,   277,   278,   278,   277,   281,   177,   281,   179,     6,
     218,   250,   251,    59,   176,   176,     6,   176,   216,   216,
     331,   331,     7,    27,   222,   180,   180,   190,   176,   179,
     296,    27,   309,   310,   311,   338,    23,    82,   105,   106,
     188,   271,   319,   320,   180,   292,   336,    27,   336,    27,
     189,   340,   341,   336,   292,   216,   176,   216,   216,   344,
     178,     4,   245,    82,   180,   190,   297,   298,   299,   300,
     301,   302,   344,     4,   335,   174,   176,   190,     4,     4,
      23,   319,   174,   176,   180,   337,   335,   179,    27,   174,
     208,   179,   180,    57,   179,   331,   176,   180,   298,   176,
     176,    62,    80,   163,   331,   179,    27,   310,   335,   331,
     331,   176,   320,   331,     4,   179,   313,   216,   341,   178,
     216,   176,   216,    23,   187,   308,   295,   190,   335,   312,
       4,   335,   177,   335,   335,   336,   331,   312,   180,   180,
     180,   190,   179,   180,   199,   307,   314,   315,   331,   265,
     313,   180,   336,   336,   312,   315,   338,   335,   178,   180,
      23,   281,   335,   336,   316,   176,   179,   303,   177,   318,
     216,   336,   265,   336,   180,   178,   152,   176,   179,   317,
     331,   216,   336,   176,   180
    };
  }

/* YYR1[RULE-NUM] -- Symbol kind of the left-hand side of rule RULE-NUM.  */
  private static final short[] yyr1_ = yyr1_init();
  private static final short[] yyr1_init()
  {
    return new short[]
    {
       0,   185,   186,   187,   187,   187,   187,   187,   187,   187,
     187,   187,   187,   187,   187,   187,   187,   187,   187,   187,
     187,   187,   187,   187,   187,   187,   187,   187,   187,   187,
     187,   187,   187,   187,   187,   187,   187,   187,   187,   187,
     187,   187,   187,   187,   187,   187,   187,   187,   187,   187,
     187,   187,   187,   187,   187,   187,   187,   187,   187,   187,
     187,   187,   187,   187,   187,   187,   187,   187,   187,   187,
     187,   187,   187,   187,   188,   188,   188,   188,   188,   188,
     188,   188,   189,   189,   190,   190,   191,   191,   192,   192,
     193,   193,   194,   194,   195,   195,   195,   195,   196,   196,
     197,   197,   198,   199,   199,   200,   200,   200,   200,   200,
     201,   201,   202,   202,   202,   202,   202,   203,   202,   204,
     202,   202,   202,   202,   202,   205,   205,   206,   207,   208,
     208,   209,   209,   210,   210,   211,   211,   212,   212,   213,
     213,   214,   214,   215,   215,   216,   216,   217,   217,   217,
     217,   218,   218,   218,   218,   218,   218,   218,   218,   218,
     218,   218,   218,   218,   218,   218,   218,   218,   218,   219,
     218,   218,   218,   218,   218,   218,   220,   220,   221,   221,
     222,   222,   223,   223,   224,   224,   225,   226,   226,   227,
     228,   228,   229,   229,   231,   230,   232,   230,   233,   233,
     234,   234,   235,   235,   236,   236,   236,   238,   237,   240,
     239,   242,   241,   243,   243,   244,   245,   245,   246,   246,
     247,   247,   248,   248,   249,   249,   249,   249,   250,   250,
     251,   251,   252,   252,   253,   253,   253,   253,   254,   254,
     254,   254,   254,   255,   256,   256,   257,   257,   258,   258,
     259,   259,   260,   260,   261,   261,   262,   262,   263,   263,
     264,   264,   265,   265,   266,   266,   267,   267,   268,   268,
     269,   269,   270,   270,   271,   271,   271,   271,   272,   272,
     273,   273,   274,   274,   275,   275,   276,   276,   276,   276,
     277,   277,   277,   278,   278,   279,   279,   280,   280,   281,
     281,   282,   282,   282,   283,   283,   284,   284,   284,   284,
     285,   285,   285,   286,   286,   287,   287,   288,   288,   289,
     290,   290,   291,   291,   292,   292,   293,   293,   293,   293,
     293,   293,   294,   294,   294,   295,   295,   296,   296,   296,
     297,   297,   298,   298,   299,   300,   300,   300,   300,   301,
     301,   302,   303,   303,   304,   304,   305,   305,   306,   306,
     307,   307,   308,   308,   308,   308,   308,   308,   308,   308,
     308,   308,   309,   309,   310,   310,   311,   311,   312,   312,
     312,   313,   313,   314,   314,   316,   315,   317,   317,   317,
     318,   318,   319,   319,   320,   320,   321,   322,   322,   323,
     324,   324,   324,   325,   325,   326,   326,   326,   326,   328,
     327,   329,   329,   329,   330,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   331,   331,   331,   331,   331,   331,   331,   331,   331,
     331,   332,   332,   333,   334,   335,   336,   337,   338,   338,
     339,   339,   340,   340,   341,   341,   342,   342,   342,   342,
     343,   342,   344,   344,   345,   345,   345,   346,   346,   346,
     347,   347,   348,   348,   348,   348,   349,   349,   349,   349,
     349,   349,   349,   349,   350,   350,   350,   350,   350,   350,
     350,   350,   350,   350,   351,   351,   351,   351,   352,   352,
     353,   354,   354,   354,   354,   354,   355,   355,   356,   356,
     356,   356,   357,   357,   357,   357,   357,   358,   358,   358,
     358,   359,   359,   359,   360,   360,   361,   361,   361,   361,
     361,   361,   362,   362,   362,   363,   363,   363,   364,   365,
     365,   366,   366,   367,   367,   367,   367,   367,   367,   367,
     368,   368,   368,   368,   369,   369,   369,   369,   369,   369,
     369,   369,   370,   370,   370,   370,   371,   371,   371,   371,
     371,   371,   371,   372,   372,   373
    };
  }

/* YYR2[RULE-NUM] -- Number of symbols on the right-hand side of rule RULE-NUM.  */
  private static final byte[] yyr2_ = yyr2_init();
  private static final byte[] yyr2_init()
  {
    return new byte[]
    {
       0,     2,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     1,     1,     2,     0,     1,     1,
       1,     1,     1,     1,     1,     1,     1,     1,     1,     2,
       1,     3,     5,     1,     2,     1,     1,     1,     1,     1,
       1,     3,     1,     1,     2,     4,     3,     0,     6,     0,
       5,     3,     4,     3,     4,     1,     1,     6,     6,     0,
       1,     3,     1,     3,     1,     3,     1,     1,     2,     1,
       3,     1,     3,     3,     1,     2,     0,     1,     1,     2,
       4,     3,     1,     1,     5,     7,     9,     5,     3,     3,
       3,     3,     3,     3,     1,     2,     6,     7,     9,     0,
       6,     1,     6,     3,     2,     3,     0,     9,     1,     3,
       0,     1,     0,     4,     1,     3,     1,     1,     1,    13,
       0,     1,     0,     1,     0,    10,     0,     9,     1,     2,
       1,     2,     0,     1,     1,     1,     1,     0,     7,     0,
       8,     0,     9,     0,     2,     5,     0,     2,     0,     2,
       0,     2,     0,     2,     1,     2,     4,     3,     1,     4,
       1,     4,     1,     4,     3,     4,     4,     5,     0,     5,
       5,     4,     4,     7,     0,     2,     1,     3,     4,     4,
       1,     3,     1,     4,     5,     6,     1,     3,     6,     7,
       3,     6,     2,     0,     1,     3,     2,     1,     0,     1,
       7,     9,     0,     1,     1,     2,     1,     1,     1,     1,
       1,     3,     3,     3,     3,     3,     1,     2,     1,     1,
       1,     1,     1,     1,     3,     3,     3,     3,     3,     0,
       2,     2,     4,     3,     1,     3,     2,     4,     4,     3,
       3,     1,     3,     3,     2,     1,     1,     3,     1,     1,
       3,     1,     1,     3,     2,     0,     4,     3,     4,     5,
      12,     1,     1,     2,     3,     1,     3,     1,     2,     3,
       1,     2,     2,     2,     3,     3,     3,     4,     3,     1,
       1,     3,     1,     3,     1,     1,     0,     1,     0,     1,
       1,     2,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     3,     1,     2,     4,     5,     7,     0,     2,
       3,     0,     3,     0,     1,     0,     9,     1,     3,     3,
       0,     3,     3,     1,     4,     4,     4,     3,     1,     1,
       0,     3,     1,     0,     1,     3,     4,     2,     1,     0,
      10,     3,     2,     3,     2,     1,     6,     5,     3,     4,
       2,     2,     3,     3,     3,     3,     3,     3,     3,     3,
       3,     3,     3,     3,     3,     2,     2,     2,     2,     3,
       3,     3,     3,     3,     3,     3,     3,     3,     3,     3,
       3,     3,     3,     3,     3,     3,     3,     2,     2,     2,
       2,     3,     3,     3,     3,     3,     3,     3,     3,     3,
       3,     3,     3,     1,     1,     5,     4,     3,     1,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     1,     3,
       2,     1,     2,     4,     2,     2,     1,     2,     2,     3,
       1,    13,    12,     1,     1,     0,     0,     0,     0,     1,
       0,     5,     3,     1,     1,     2,     2,     2,     4,     4,
       0,     3,     1,     1,     1,     1,     3,     0,     1,     1,
       0,     1,     4,     3,     1,     3,     1,     1,     3,     2,
       3,     1,     1,     1,     1,     1,     1,     1,     1,     1,
       1,     1,     1,     1,     3,     3,     5,     5,     0,     1,
       1,     1,     3,     1,     1,     1,     1,     1,     1,     3,
       1,     1,     1,     4,     4,     4,     1,     1,     1,     3,
       3,     1,     4,     2,     3,     3,     1,     4,     3,     3,
       3,     3,     1,     3,     1,     1,     3,     1,     1,     0,
       1,     3,     1,     3,     1,     4,     2,     2,     6,     4,
       2,     2,     1,     2,     1,     4,     3,     3,     3,     3,
       6,     3,     1,     1,     2,     1,     5,     4,     2,     2,
       4,     2,     2,     1,     3,     1
    };
  }




  /* YYTRANSLATE_(TOKEN-NUM) -- Symbol number corresponding to TOKEN-NUM
     as returned by yylex, with out-of-bounds checking.  */
  private static final SymbolKind yytranslate_(int t)
  {
    // Last valid token kind.
    int code_max = 412;
    if (t <= 0)
      return SymbolKind.S_YYEOF;
    else if (t <= code_max)
      return SymbolKind.get(yytranslate_table_[t]);
    else
      return SymbolKind.S_YYUNDEF;
  }
  private static final short[] yytranslate_table_ = yytranslate_table_init();
  private static final short[] yytranslate_table_init()
  {
    return new short[]
    {
       0,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,    17,   183,     2,   184,    16,     2,     2,
     177,   178,    14,    12,   174,    13,    11,    15,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     6,   176,
       9,     4,    10,     5,    19,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,   181,     2,   175,     8,     2,   182,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,   179,     7,   180,    18,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     2,     2,     2,     2,
       2,     2,     2,     2,     2,     2,     1,     2,     3,    20,
      21,    22,    23,    24,    25,    26,    27,    28,    29,    30,
      31,    32,    33,    34,    35,    36,    37,    38,    39,    40,
      41,    42,    43,    44,    45,    46,    47,    48,    49,    50,
      51,    52,    53,    54,    55,    56,    57,    58,    59,    60,
      61,    62,    63,    64,    65,    66,    67,    68,    69,    70,
      71,    72,    73,    74,    75,    76,    77,    78,    79,    80,
      81,    82,    83,    84,    85,    86,    87,    88,    89,    90,
      91,    92,    93,    94,    95,    96,    97,    98,    99,   100,
     101,   102,   103,   104,   105,   106,   107,   108,   109,   110,
     111,   112,   113,   114,   115,   116,   117,   118,   119,   120,
     121,   122,   123,   124,   125,   126,   127,   128,   129,   130,
     131,   132,   133,   134,   135,   136,   137,   138,   139,   140,
     141,   142,   143,   144,   145,   146,   147,   148,   149,   150,
     151,   152,   153,   154,   155,   156,   157,   158,   159,   160,
     161,   162,   163,   164,   165,   166,   167,   168,   169,   170,
     171,   172,   173
    };
  }


  private static final int YYLAST_ = 11317;
  private static final int YYEMPTY_ = -2;
  private static final int YYFINAL_ = 3;
  private static final int YYNTOKENS_ = 185;


}
/* "src/main/grammar/zend_language_parser.y":1730  */


/* php-src's epilogue, zend_yytnamerr, is ported in ZendLexer. */
