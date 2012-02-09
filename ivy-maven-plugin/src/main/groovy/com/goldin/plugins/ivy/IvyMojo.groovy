package com.goldin.plugins.ivy

import static com.goldin.plugins.common.GMojoUtils.*
import com.goldin.gcommons.GCommons
import com.goldin.plugins.common.BaseGroovyMojo3
import org.apache.ivy.Ivy
import org.apache.ivy.core.module.descriptor.MDArtifact
import org.apache.ivy.core.report.ArtifactDownloadReport
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.plugin.dependency.fromConfiguration.ArtifactItem
import org.gcontracts.annotations.Ensures
import org.gcontracts.annotations.Requires
import org.jfrog.maven.annomojo.annotations.MojoGoal
import org.jfrog.maven.annomojo.annotations.MojoParameter
import org.jfrog.maven.annomojo.annotations.MojoPhase
import org.jfrog.maven.annomojo.annotations.MojoRequiresDependencyResolution
import org.sonatype.aether.impl.internal.DefaultRepositorySystem
import org.apache.ivy.core.settings.IvySettings


/**
 * Plugin that delegates artifacts resolving to Ivy, adds dependencies resolved to the Maven scope or
 * copies them to local directory.
 */
@MojoGoal ( 'ivy' )
@MojoPhase ( 'initialize' )
@MojoRequiresDependencyResolution( 'test' )
class IvyMojo extends BaseGroovyMojo3
{
    public static final String IVY_PREFIX = 'ivy.'


   /**
    * Ivy settings file: http://ant.apache.org/ivy/history/latest-milestone/settings.html.
    */
    @MojoParameter ( required = true )
    public String ivyconf

    /**
     * Ivy file: http://ant.apache.org/ivy/history/latest-milestone/ivyfile.html.
     */
    @MojoParameter ( required = false )
    public String ivy

    /**
     * Maven-style {@code <dependencies>}.
     */
    @MojoParameter ( required = false )
    public ArtifactItem[] dependencies

    /**
     * Maven scope to add the dependencies resolved to: "compile", "runtime", "test", etc.
     * Similar to Ivy's <cachepath>: http://ant.apache.org/ivy/history/latest-milestone/use/cachepath.html.
     */
    @MojoParameter ( required = false )
    public String scope

    /**
     * Directory to copy resolved dependencies to.
     */
    @MojoParameter ( required = false )
    public File dir


    /**
     * Whether plugin should log verbosely (verbose = true), regularly (verbose is null) or not at all (verbose = false).
     */
    @MojoParameter ( required = false )
    public Boolean verbose
    private boolean logVerbosely(){ ( verbose ) }
    private boolean logNormally (){ ( verbose ) || ( verbose == null ) }


    @Override
    @Requires({ ivyconf })
    void doExecute ()
    {
        Ivy ivyInstance = addIvyResolver( url( ivyconf ))

        if ( scope || dir )
        {
            assert ( ivy || dependencies ), "Either <ivy> or <dependencies> (or both) need to be specified when <scope> or <dir> are used."
            final dependencies = resolveAllDependencies( ivyInstance, ( ivy ? url( ivy ) : null ), dependencies )

            if ( scope ){ addArtifacts  ( scope, dependencies ) }
            if ( dir   ){ copyArtifacts ( dir,   dependencies ) }
        }
    }


    /**
     * Adds Ivy resolver to Aether RepositorySystem.
     * @return configured {@link Ivy} instance.
     */
    @Requires({ ivyconfUrl })
    @Ensures({ result })
    private Ivy addIvyResolver ( URL ivyconfUrl )
    {
        IvySettings settings = new IvySettings()
        settings.load( ivyconfUrl )
        final ivyInstance = Ivy.newInstance( settings )

        assert repoSystem instanceof DefaultRepositorySystem
        (( DefaultRepositorySystem ) repoSystem ).artifactResolver = new IvyArtifactResolver( repoSystem.artifactResolver, ivyInstance )

        if ( logNormally() )
        {
            log.info( "Added Ivy artifacts resolver based on \"$ivyconfUrl\"" )
        }

        ivyInstance
    }

    /**
     * Converts path specified to URL.
     *
     * @param s path of disk file or jar-located resource.
     * @return path's URL
     */
    @Requires({ s })
    @Ensures({ result })
    private URL url( String s )
    {
        s.trim().with { ( startsWith( 'jar:' ) || startsWith( 'file:' )) ? new URL( s ) : new File( s ).toURL() }
    }


    /**
     * Resolves dependencies specified and retrieves their local paths.
     *
     * @param ivyInstance  configured {@link Ivy} instance
     * @param ivyFile      "ivy.xml" file
     * @param dependencies Maven-style dependencies
     * @return             local paths of dependencies resolved
     */
    @Requires({ ivyInstance && ( ivyFile || dependencies ) })
    @Ensures({ result && result.every{ it.file.file } })
    private List<Artifact> resolveAllDependencies ( Ivy ivyInstance, URL ivyFile, ArtifactItem[] dependencies )
    {
        final ivyArtifacts   = ( ivyFile      ? resolveIvyDependencies  ( ivyInstance, ivyFile ) : [] )
        final mavenArtifacts = ( dependencies ? resolveMavenDependencies( dependencies         ) : [] )
        ivyArtifacts + mavenArtifacts
    }


    /**
     * Resolve dependencies specified in Ivy file.
     *
     * @param ivyInstance  configured {@link Ivy} instance
     * @param ivyFile ivy dependencies file
     * @return artifacts resolved
     */
    @Requires({ ivyInstance && ivyFile })
    @Ensures({ result })
    @SuppressWarnings( 'GroovySetterCallCanBePropertyAccess' )
    private List<Artifact> resolveIvyDependencies ( Ivy ivyInstance, URL ivyFile )
    {
        final options = new ResolveOptions()
        options.setConfs([ 'default' ] as String[] )
        options.setLog( logVerbosely() ? 'default' : 'download-only' )
        final report  = ivyInstance.resolve( ivyFile, options )

        report.allArtifactsReports.collect {
            ArtifactDownloadReport artifactReport ->
            assert artifactReport.artifact instanceof MDArtifact

            /**
             * Help me God, Ivy. See http://db.tt/9Cf1X4bH.
             */
            Map    attributes = (( MDArtifact ) artifactReport.artifact ).md.metadataArtifact.id.moduleRevisionId.attributes
            String groupId    = attributes[ 'organisation' ]
            String artifactId = attributes[ 'module'       ]
            String version    = attributes[ 'revision'     ]
            String classifier = artifactReport.artifactOrigin.artifact.name // artifact name ("core/annotations" - http://goo.gl/se95h) plays as classifier
            File   localFile  = artifactReport.localFile

            if ( logVerbosely())
            {
                log.info( "[${ ivyFile }] => \"$groupId:$artifactId:$classifier:$version\" (${ localFile.canonicalPath })" )
            }

            artifact( IvyMojo.IVY_PREFIX + groupId, artifactId, version, file().extension( localFile ), classifier, localFile )
        }
    }


    /**
     * Resolve dependencies specified inline as Maven {@code <dependencies>}.
     *
     * @param dependencies dependencies to resolve
     * @return artifacts resolved
     */
    @Requires({ dependencies })
    @Ensures({ result && ( result.size() == dependencies.size()) })
    private List<Artifact> resolveMavenDependencies ( ArtifactItem[] dependencies )
    {
        dependencies.collect {
            ArtifactItem d -> resolveArtifact( artifact( d.groupId, d.artifactId, d.version, d.type, d.classifier, null ))
        }
    }


    /**
     * Creates new Maven {@link Artifact}.
     *
     * @param groupId    artifact {@code <groupId>}
     * @param artifactId artifact {@code <artifactId>}
     * @param version    artifact {@code <version>}
     * @param type       artifact {@code <type>}
     * @param classifier artifact {@code <classifier>}
     * @param file       artifact local file, may be {@code null}
     *
     * @return new Maven {@link Artifact}
     */
    @Requires({ groupId && artifactId && version })
    @Ensures({ result })
    private Artifact artifact( String groupId, String artifactId, String version, String type, String classifier, File file = null )
    {
        DefaultArtifactHandler handler = new DefaultArtifactHandler()
        handler.addedToClasspath       = true // For new artifacts to be added to compilation classpath later

        final a = new DefaultArtifact( groupId, artifactId, version, '', type, classifier, handler )
        if ( file ) { a.file = verify().file( file ) }
        a
    }


    /**
     * Adds artifacts to the scope specified.
     *
     * @param scope     Maven scope to add artifacts to: "compile", "runtime", "test", etc.
     * @param artifacts dependencies to add to the scope
     */
    @Requires({ scope && artifacts && artifacts.every{ it.file.file } })
    private void addArtifacts ( String scope, List<Artifact> artifacts )
    {
        /**
         * Two beautiful Maven hacks are coming! (thanks to Groovy being able to read/write private fields)
         */
        if ( scope == 'plugin-runtime' )
        {   /**
             * Hack #1: adding jars to plugin's classloader.
             */
            assert this.class.classLoader instanceof URLClassLoader
            artifacts*.file.each {
                (( URLClassLoader ) this.class.classLoader ).addURL( it.toURL())
            }
        }
        else
        {   /**
             * Hack #2: adding jars to Maven's scope and compilation classpath.
             */
            artifacts.each { it.scope = scope }
            project.setResolvedArtifacts( new HashSet<Artifact>( project.resolvedArtifacts + artifacts ))
        }

        final message = "${ artifacts.size() } artifact${ GCommons.general().s( artifacts.size())} added to \"$scope\" scope: "

        if ( logVerbosely())
        {
            log.info( message + artifacts.collect { "\"$it\" (${ it.file })"  })
        }
        else if ( logNormally())
        {
            log.info( message + artifacts )
        }
    }


    /**
     * Copies artifacts to directory specified.
     *
     * @param directory directory to copy the artifacts to
     * @param artifacts artifacts to copy
     */
    @Requires({ directory && artifacts && artifacts.every{ it.file.file } })
    @Ensures({ artifacts.every{ new File( directory, it.file.name ).file } })
    private void copyArtifacts ( File directory, List<Artifact> artifacts )
    {
        Map<Artifact, File> filesCopied = artifacts.inject([ : ]){
            Map m, Artifact a -> m[ a ] = file().copy( a.file, directory )
                                 m
        }

        final message = "${ artifacts.size() } artifact${ GCommons.general().s(artifacts.size())} copied to \"${ directory.canonicalPath }\": "

        if ( logVerbosely())
        {
            log.info( message + artifacts.collect { "\"$it\" => \"${ filesCopied[ it ] }\""  })
        }
        else if ( logNormally())
        {
            log.info( message + artifacts )
        }
    }
}
