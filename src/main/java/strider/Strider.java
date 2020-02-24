package strider;

import static java.lang.String.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.logging.*;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

final class InvalidEdgeException extends Exception {
    private static final long serialVersionUID = -4675607277534425221L;

    public InvalidEdgeException(final String message) {
        super(message);
    }
}

/**
 * Fetch partial answers including edge.
 */
public class Strider {
    static Logger logger;

    public Strider() {
        logger = Logger.getLogger("org.neo4j.examples.server.plugins");
        logger.setLevel(Level.ALL);
    }

    private class Partial implements Cloneable {
        HashMap<String, Node> nodes;
        HashMap<String, Relationship> edges;

        public Partial() {
            this.nodes = new HashMap<String, Node>();
            this.edges = new HashMap<String, Relationship>();
        }

        public List<Node> add(final Relationship edge) throws InvalidEdgeException {
            final String edgeQID = edge.getProperty("qid").toString();
            // Check that edge QID has not been traversed.
            if (edges.containsKey(edgeQID)) {
                logger.info(String.format(
                    "Repeat edge QID for %s. Aborting.",
                    edge.getProperty("kid").toString()
                ));
                throw new InvalidEdgeException("");
            }
            final Node[] _nodes = { edge.getStartNode(), edge.getEndNode() };
            // Check that node KIDs are consistent.
            for (final Node _node : _nodes) {
                final String nodeQID = _node.getProperty("qid").toString();
                if (!nodes.containsKey(nodeQID))
                    continue;
                if (!nodes.get(nodeQID).equals(_node)) {
                    logger.info(String.format(
                        "Inconsistent node KID for %s. Aborting.",
                        _node.getProperty("kid").toString()
                    ));
                    throw new InvalidEdgeException("");
                }
            }
            // Add to partial.
            edges.put(edgeQID, edge);
            final List<Node> outNodes = new ArrayList<Node>();
            for (final Node _node : _nodes) {
                final String nodeQID = _node.getProperty("qid").toString();
                if (nodes.containsKey((nodeQID))) continue;
                nodes.put(nodeQID, _node);
                outNodes.add(_node);
            }
            return outNodes;
        }

        public Partial clone() {
            final Partial copy = new Partial();
            copy.nodes = new HashMap<String, Node>(nodes);
            copy.edges = new HashMap<String, Relationship>(edges);
            return copy;
        }

        public boolean equals(final Object other) {
            final Partial otherPartial = (Partial) other;
            return nodes.equals(otherPartial.nodes) && edges.equals(otherPartial.edges);
        }

        public int hashCode() {
            return 0;
        }

        public String toString() {
            return "(" + nodes.toString() + ", " + edges.toString() + ")";
        }
    }

    public HashSet<Partial> getPath(final Relationship edge) {
        final Partial partial = new Partial();
        return getPath(edge, partial, 0);
    }

    public HashSet<Partial> getPath(final Relationship edge, Partial prefix, final int level) {
        // add this edge to the partial, and all edges attached to it
        final HashSet<Partial> partials = new HashSet<Partial>();

        prefix = prefix.clone(); // prefix comes in as a shallow copy, make it deep

        final List<Node> nodes;
        try {
            nodes = prefix.add(edge);
        } catch (final InvalidEdgeException ex) {
            return partials;
        }

        partials.add(prefix);

        logger.info(String.format("[%d] processing edge: %s", level, edge.getProperty("kid").toString()));
        // final Node[] nodes = { edge.getStartNode(), edge.getEndNode() };

        for (final Node node : nodes) {
            logger.info(format("[%d] from node: %s", level, node.getProperty("kid").toString()));
            for (final Relationship _edge : node.getRelationships()) {
                // don't follow another edge with the same binding
                if (_edge.getProperty("qid").toString().equals(edge.getProperty("qid").toString()))
                    continue;
                logger.info(format("[%d] following edge: %s", level, _edge.getProperty("kid").toString()));
                final HashSet<Partial> newPartials = new HashSet<Partial>();
                for (final Partial partial : partials) {
                    newPartials.addAll(getPath(_edge, partial, level + 1));
                }
                partials.addAll(newPartials);
            }
        }
        logger.info(String.format("[%d] returning: %s", level,
            partials.toString()));
        return partials;
    }

    @Procedure
    @Description("strider.getPaths(r) yields a stream of strings as 'id'.")
    public Stream<EdgeIdResult> getPaths(@Name("edge") final Relationship edge) {
        logger.info("Running getPaths()...");

        final HashSet<Partial> partials = getPath(edge);
        return partials.stream().map(partial -> new EdgeIdResult(partial));
    }

    public class EdgeIdResult {
        public Map<String, Node> nodes;
        public Map<String, Relationship> edges;

        public EdgeIdResult(final Partial path) {
            this.nodes = new HashMap<String, Node>(path.nodes);
            this.edges = new HashMap<String, Relationship>(path.edges);
        }
    }
}
