package de.mytoys.maven.plugins.debug;

/*
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.Restriction;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.RuntimeInformation;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.dependency.utils.DependencyUtil;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.StrictPatternIncludesArtifactFilter;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.apache.maven.shared.dependency.tree.filter.AncestorOrSelfDependencyNodeFilter;
import org.apache.maven.shared.dependency.tree.filter.AndDependencyNodeFilter;
import org.apache.maven.shared.dependency.tree.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.tree.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.tree.filter.StateDependencyNodeFilter;
import org.apache.maven.shared.dependency.tree.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.tree.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.tree.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.tree.traversal.FilteringDependencyNodeVisitor;
import org.apache.maven.shared.dependency.tree.traversal.SerializingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.tree.traversal.SerializingDependencyNodeVisitor.TreeTokens;

/**
 *
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 */
abstract class AbstractDebugMojo extends AbstractMojo {
	// fields -----------------------------------------------------------------

	/**
	 * The Maven project.
	 *
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	protected MavenProject project;
	/**
	 * The artifact repository to use.
	 *
	 * @parameter expression="${localRepository}"
	 * @required
	 * @readonly
	 */
	protected ArtifactRepository localRepository;
	/**
	 * The artifact factory to use.
	 *
	 * @component
	 * @required
	 * @readonly
	 */
	protected ArtifactFactory artifactFactory;
	/**
	 * The artifact metadata source to use.
	 *
	 * @component
	 * @required
	 * @readonly
	 */
	protected ArtifactMetadataSource artifactMetadataSource;
	/**
	 * The artifact collector to use.
	 *
	 * @component
	 * @required
	 * @readonly
	 */
	protected ArtifactCollector artifactCollector;
	/**
	 * The dependency tree builder to use.
	 *
	 * @component
	 * @required
	 * @readonly
	 */
	protected DependencyTreeBuilder dependencyTreeBuilder;
	/**
	 * The scope to filter by when resolving the dependency tree, or <code>null</code> to include dependencies from
	 * all scopes. Note that this feature does not currently work due to MNG-3236.
	 *
	 * @since 2.0-alpha-5
	 * @see <a href="http://jira.codehaus.org/browse/MNG-3236">MNG-3236</a>
	 *
	 * @parameter expression="${scope}"
	 */
	protected String scope;
	/**
	 * Whether to include omitted nodes in the serialized dependency tree.
	 *
	 * @since 2.0-alpha-6
	 *
	 * @parameter expression="${verbose}" default-value="false"
	 */
	protected boolean verbose;
	/**
	 * The token set name to use when outputting the dependency tree. Possible values are <code>whitespace</code>,
	 * <code>standard</code> or <code>extended</code>, which use whitespace, standard or extended ASCII sets
	 * respectively.
	 *
	 * @since 2.0-alpha-6
	 *
	 * @parameter expression="${tokens}" default-value="standard"
	 */
	protected String tokens;
	/**
	 * A comma-separated list of artifacts to filter the serialized dependency tree by, or <code>null</code> not to
	 * filter the dependency tree. The artifact syntax is defined by <code>StrictPatternIncludesArtifactFilter</code>.
	 *
	 * @see StrictPatternIncludesArtifactFilter
	 * @since 2.0-alpha-6
	 *
	 * @parameter expression="${includes}"
	 */
	protected String includes;
	/**
	 * A comma-separated list of artifacts to filter from the serialized dependency tree, or <code>null</code> not to
	 * filter any artifacts from the dependency tree. The artifact syntax is defined by
	 * <code>StrictPatternExcludesArtifactFilter</code>.
	 *
	 * @see StrictPatternExcludesArtifactFilter
	 * @since 2.0-alpha-6
	 *
	 * @parameter expression="${excludes}"
	 */
	protected String excludes;
	/**
	 * Runtime Information used to check the Maven version
	 * @since 2.0
	 * @component role="org.apache.maven.execution.RuntimeInformation"
	 */
	protected RuntimeInformation rti;
	/**
	 * The computed dependency tree root node of the Maven project.
	 */
	protected DependencyNode rootNode;
	/**
	 * Whether to append outputs into the output file or overwrite it.
	 * 
	 * @parameter expression="${appendOutput}" default-value="false"
	 * @since 2.2
	 */
	protected boolean appendOutput;

	/**
	 * Whether to fail build when a conflict was found
	 * @parameter expression="${failOnConflict}" default-value="false"
	 */
	protected boolean failOnConflict;
	// Mojo methods -----------------------------------------------------------

	/*
	 * @see org.apache.maven.plugin.Mojo#execute()
	 */
	public void execute()
					throws MojoExecutionException, MojoFailureException {

		ArtifactVersion detectedMavenVersion = rti.getApplicationVersion();
		VersionRange vr;
		try {
			vr = VersionRange.createFromVersionSpec("[2.0.8,)");
			if (!containsVersion(vr, detectedMavenVersion)) {
				getLog().warn(
								"The tree mojo requires at least Maven 2.0.8 to function properly. You may get erroneous results on earlier versions");
			}
		} catch (InvalidVersionSpecificationException e) {
			throw new MojoExecutionException(e.getLocalizedMessage(), e);
		}


		ArtifactFilter artifactFilter = createResolvingArtifactFilter();

		try {
			// TODO: note that filter does not get applied due to MNG-3236

			rootNode =
							dependencyTreeBuilder.buildDependencyTree(project, localRepository, artifactFactory,
							artifactMetadataSource, artifactFilter, artifactCollector);

			String dependencyTreeString = serializeDependencyTree(rootNode);

			DependencyUtil.log(dependencyTreeString, getLog());

			postprocessResult();

		} catch (DependencyTreeBuilderException exception) {
			throw new MojoExecutionException("Cannot build project dependency tree", exception);
		} catch (IOException exception) {
			throw new MojoExecutionException("Cannot serialise project dependency tree", exception);
		}

	}

	protected abstract void postprocessResult() throws MojoExecutionException;

	// public methods ---------------------------------------------------------
	/**
	 * Gets the Maven project used by this mojo.
	 *
	 * @return the Maven project
	 */
	public MavenProject getProject() {
		return project;
	}

	/**
	 * Gets the computed dependency tree root node for the Maven project.
	 *
	 * @return the dependency tree root node
	 */
	public DependencyNode getDependencyTree() {
		return rootNode;
	}

	// protected methods --------------------------------------------------------
	/**
	 * Gets the artifact filter to use when resolving the dependency tree.
	 *
	 * @return the artifact filter
	 */
	protected ArtifactFilter createResolvingArtifactFilter() {
		ArtifactFilter filter;

		// filter scope
		if (scope != null) {
			getLog().debug("+ Resolving dependency tree for scope '" + scope + "'");

			filter = new ScopeArtifactFilter(scope);
		} else {
			filter = null;
		}

		return filter;
	}

	/**
	 * Serializes the specified dependency tree to a string.
	 *
	 * @param rootNode
	 *            the dependency tree root node to serialize
	 * @return the serialized dependency tree
	 */
	protected String serializeDependencyTree(DependencyNode rootNode) {
		StringWriter writer = new StringWriter();

		DependencyNodeVisitor visitor = getSerializingDependencyNodeVisitor(writer);

		// TODO: remove the need for this when the serializer can calculate last nodes from visitor calls only
		visitor = new BuildingDependencyNodeVisitor(visitor);

		DependencyNodeFilter filter = createDependencyNodeFilter();

		if (filter != null) {
			CollectingDependencyNodeVisitor collectingVisitor = new CollectingDependencyNodeVisitor();
			DependencyNodeVisitor firstPassVisitor = new FilteringDependencyNodeVisitor(collectingVisitor, filter);
			rootNode.accept(firstPassVisitor);

			DependencyNodeFilter secondPassFilter = new AncestorOrSelfDependencyNodeFilter(collectingVisitor.getNodes());
			visitor = new FilteringDependencyNodeVisitor(visitor, secondPassFilter);
		}

		rootNode.accept(visitor);

		return writer.toString();
	}

	abstract DependencyNodeVisitor getSerializingDependencyNodeVisitor(Writer writer);

	/**
	 * Gets the tree tokens instance for the specified name.
	 *
	 * @param tokens
	 *            the tree tokens name
	 * @return the <code>TreeTokens</code> instance
	 */
	protected TreeTokens toTreeTokens(String tokens) {
		TreeTokens treeTokens;

		if ("whitespace".equals(tokens)) {
			getLog().debug("+ Using whitespace tree tokens");

			treeTokens = SerializingDependencyNodeVisitor.WHITESPACE_TOKENS;
		} else if ("extended".equals(tokens)) {
			getLog().debug("+ Using extended tree tokens");

			treeTokens = SerializingDependencyNodeVisitor.EXTENDED_TOKENS;
		} else {
			treeTokens = SerializingDependencyNodeVisitor.STANDARD_TOKENS;
		}

		return treeTokens;
	}

	/**
	 * Gets the dependency node filter to use when serializing the dependency tree.
	 *
	 * @return the dependency node filter, or <code>null</code> if none required
	 */
	protected DependencyNodeFilter createDependencyNodeFilter() {
		List<DependencyNodeFilter> filters = new ArrayList<DependencyNodeFilter>();

		// filter node states
		if (!verbose) {
			getLog().debug("+ Filtering omitted nodes from dependency tree");

			filters.add(StateDependencyNodeFilter.INCLUDED);
		}

		// filter includes
		if (includes != null) {
			List<String> patterns = Arrays.asList(includes.split(","));

			getLog().debug("+ Filtering dependency tree by artifact include patterns: " + patterns);

			ArtifactFilter artifactFilter = new StrictPatternIncludesArtifactFilter(patterns);
			filters.add(new ArtifactDependencyNodeFilter(artifactFilter));
		}

		// filter excludes
		if (excludes != null) {
			List<String> patterns = Arrays.asList(excludes.split(","));

			getLog().debug("+ Filtering dependency tree by artifact exclude patterns: " + patterns);

			ArtifactFilter artifactFilter = new StrictPatternExcludesArtifactFilter(patterns);
			filters.add(new ArtifactDependencyNodeFilter(artifactFilter));
		}

		return filters.isEmpty() ? null : new AndDependencyNodeFilter(filters);
	}

	//following is required because the version handling in maven code
	//doesn't work properly. I ripped it out of the enforcer rules.
	/**
	 * Copied from Artifact.VersionRange. This is tweaked to handle singular ranges properly. Currently the default
	 * containsVersion method assumes a singular version means allow everything. This method assumes that "2.0.4" ==
	 * "[2.0.4,)"
	 *
	 * @param allowedRange range of allowed versions.
	 * @param theVersion the version to be checked.
	 * @return true if the version is contained by the range.
	 */
	public static boolean containsVersion(VersionRange allowedRange, ArtifactVersion theVersion) {
		ArtifactVersion recommendedVersion = allowedRange.getRecommendedVersion();
		if (recommendedVersion == null) {
			List<Restriction> restrictions = allowedRange.getRestrictions();
			for (Restriction restriction : restrictions) {
				if (restriction.containsVersion(theVersion)) {
					return true;
				}
			}
		}

		// only singular versions ever have a recommendedVersion
		return recommendedVersion.compareTo(theVersion) <= 0;
	}
}