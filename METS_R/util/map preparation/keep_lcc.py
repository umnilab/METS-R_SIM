import xml.etree.ElementTree as ET
import networkx as nx
import sys

def main(input_file, output_file):
    # Parse the XML network file
    tree = ET.parse(input_file)
    root = tree.getroot()

    # Build a directed graph for connectivity analysis
    G = nx.DiGraph()

    # Add nodes from the network
    for node in root.findall("node"):
        node_id = node.get("id")
        G.add_node(node_id)

    # Add edges based on the 'from' and 'to' attributes of each edge
    edge_elements = root.findall("edge")
    for edge in edge_elements:
        from_node = edge.get("from")
        to_node = edge.get("to")
        if from_node and to_node:
            G.add_edge(from_node, to_node)

    # Identify the largest *strongly* connected component in a directed graph
    largest_cc = set(max(nx.strongly_connected_components(G), key=len))
    print("Largest Connected Component has", len(largest_cc), "nodes.")

    # Remove edges that do not connect nodes within the LCC
    for edge in list(edge_elements):  # list() for safe removal during iteration
        from_node = edge.get("from")
        to_node = edge.get("to")
        if from_node not in largest_cc or to_node not in largest_cc:
            root.remove(edge)

    # Remove nodes that are not in the LCC
    for node in list(root.findall("node")):
        if node.get("id") not in largest_cc:
            root.remove(node)

    # Collect IDs of remaining edges after removal
    remaining_edges = {edge.get("id") for edge in root.findall("edge")}
    print("Remaining edges count:", len(remaining_edges))

    # Remove any <connection> elements referencing non-existent edges.
    # This loop goes over all elements and removes child elements with tag "connection"
    for parent in root.iter():
        for connection in list(parent):
            if connection.tag == "connection":
                from_edge = connection.get("from")
                to_edge = connection.get("to")
                if (from_edge not in remaining_edges or to_edge not in remaining_edges):
                    parent.remove(connection)
        
    for roundabout in list(root.findall("roundabout")):
        ref_ids = roundabout.get("edges", "").split()
        if not all(ref_id in remaining_edges for ref_id in ref_ids):
            root.remove(roundabout)

    # Save the modified network to a new file
    tree.write(output_file, encoding="UTF-8", xml_declaration=True)
    print("Network filtered to largest connected component and saved as", output_file)

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python keep_lcc.py <input_net.xml> <output_net.xml>")
    else:
        main(sys.argv[1], sys.argv[2])
