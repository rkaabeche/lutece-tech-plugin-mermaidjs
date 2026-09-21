/*
 * Copyright (c) 2002-2026, City of Paris
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice
 *     and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice
 *     and the following disclaimer in the documentation and/or other materials
 *     provided with the distribution.
 *
 *  3. Neither the name of 'Mairie de Paris' nor 'Lutece' nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * License 1.0
 */
package fr.paris.lutece.plugins.mermaidjs.template;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import freemarker.template.Template;

import fr.paris.lutece.portal.service.template.FreeMarkerAutoEscapeTestUtils;

/**
 * Guards the compatibility of the plugin-mermaidjs FreeMarker templates with BOTH values of the
 * <code>service.freemarker.templateAutoEscape</code> property (see LUT-33153).
 *
 * <p>
 * The plain helper functions (scanning the template tree, locating FreeMarker spans in the source, building the
 * FreeMarker {@code Configuration} for each mode, ...) come from lutece-core's
 * {@code FreeMarkerAutoEscapeTestUtils} (test-jar dependency in this plugin's pom.xml) instead of being duplicated
 * here.
 * </p>
 *
 * <p>
 * This test deliberately does not extend <code>LuteceTestCase</code>: it needs no container, no datasource and no
 * Lutece context, only the FreeMarker engine, so that it stays fast enough to run on every build.
 * </p>
 */
public class FreeMarkerAutoEscapeCompatibilityTest
{
    private static final String TEMPLATES_ROOT = "webapp/WEB-INF/templates";

    /** Built-ins that are a ParseException when auto-escaping is OFF (the output format is not a markup one). */
    private static final String [ ] BANNED_WHEN_OFF = {
            "?no_esc", "?esc"
    };

    /** Built-ins that are a ParseException when auto-escaping is ON (legacy escaping). */
    private static final String [ ] BANNED_WHEN_ON = {
            "?html", "?xhtml"
    };

    /** The built-in the migration introduces in bulk, and therefore the one that can be misplaced in bulk. */
    private static final String BUILTIN_MARKER = "?has_content";

    // ------------------------------------------------------------------------------------------------
    // 1. The whole template tree must parse under both configurations
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName( "Every plugin template parses with auto-escaping both on and off" )
    public void everyPluginTemplateParsesInBothModes( ) throws IOException
    {
        List<Path> listTemplates = FreeMarkerAutoEscapeTestUtils.listPluginTemplates( TEMPLATES_ROOT );

        assertTrue( listTemplates.size( ) > 0,
                "Expected to find the plugin template tree under " + TEMPLATES_ROOT + ", found " + listTemplates.size( ) + " files" );

        List<String> listFailures = new ArrayList<>( );

        for ( Path path : listTemplates )
        {
            String strSource = new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 );
            String strName = path.toString( );

            for ( boolean bAutoEscape : new boolean [ ] {
                    false, true
            } )
            {
                try
                {
                    new Template( strName, strSource, FreeMarkerAutoEscapeTestUtils.newConfiguration( bAutoEscape ) );
                }
                catch( Exception e )
                {
                    listFailures.add( strName + " [autoEscape=" + bAutoEscape + "] : " + FreeMarkerAutoEscapeTestUtils.firstLine( e.getMessage( ) ) );
                }
            }
        }

        if ( !listFailures.isEmpty( ) )
        {
            fail( listFailures.size( ) + " template(s) do not parse in both auto-escaping modes:\n  " + String.join( "\n  ", listFailures ) );
        }
    }

    @Test
    @DisplayName( "No template uses a built-in that is illegal in one of the two auto-escaping modes" )
    public void noModeSpecificBuiltInIsUsed( ) throws IOException
    {
        List<String> listFailures = new ArrayList<>( );

        for ( Path path : FreeMarkerAutoEscapeTestUtils.listPluginTemplates( TEMPLATES_ROOT ) )
        {
            String strSource = new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 );

            for ( String strBuiltIn : BANNED_WHEN_OFF )
            {
                if ( strSource.contains( strBuiltIn ) )
                {
                    listFailures.add( path + " uses " + strBuiltIn + " — ParseException when auto-escaping is off; use <#noautoesc>${x}</#noautoesc>" );
                }
            }

            for ( String strBuiltIn : BANNED_WHEN_ON )
            {
                if ( strSource.contains( strBuiltIn ) )
                {
                    listFailures.add(
                            path + " uses " + strBuiltIn + " — ParseException when auto-escaping is on; use <#outputformat \"HTML\">${x}</#outputformat>" );
                }
            }
        }

        if ( !listFailures.isEmpty( ) )
        {
            fail( listFailures.size( ) + " mode-specific built-in usage(s):\n  " + String.join( "\n  ", listFailures ) );
        }
    }

    @Test
    @DisplayName( "No FreeMarker built-in leaked into JavaScript or plain text" )
    public void noBuiltInLeakedOutsideADirective( ) throws IOException
    {
        List<String> listFailures = new ArrayList<>( );

        for ( Path path : FreeMarkerAutoEscapeTestUtils.listPluginTemplates( TEMPLATES_ROOT ) )
        {
            String strSource = new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 );
            List<int [ ]> listSpans = FreeMarkerAutoEscapeTestUtils.freeMarkerSpans( strSource );

            int nIndex = strSource.indexOf( BUILTIN_MARKER );

            while ( nIndex >= 0 )
            {
                if ( !FreeMarkerAutoEscapeTestUtils.isInsideFreeMarker( nIndex, listSpans ) )
                {
                    listFailures.add( path + " line " + FreeMarkerAutoEscapeTestUtils.lineOf( strSource, nIndex ) + " : \"" + BUILTIN_MARKER
                            + "\" sits in JavaScript or plain text, where FreeMarker never evaluates it — "
                            + "a mass rewrite of \"x != ''\" most likely caught a JavaScript comparison" );
                }

                nIndex = strSource.indexOf( BUILTIN_MARKER, nIndex + 1 );
            }
        }

        if ( !listFailures.isEmpty( ) )
        {
            fail( listFailures.size( ) + " leaked built-in(s):\n  " + String.join( "\n  ", listFailures ) );
        }
    }
}
