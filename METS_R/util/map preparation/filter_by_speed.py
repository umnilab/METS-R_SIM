#!/usr/bin/env python3
import xml.etree.ElementTree as ET
import sys
import json
from tqdm import tqdm

def filter_low_speed_residential_edges(input_net, output_net, white_list, speed_threshold=15.0, target_type="highway.residential"):
    if white_list is not None:
        with open(white_list, 'r') as f:
            whitelist_dict = json.load(f)
            whitelist = []
            for _, value in whitelist_dict.items():
                whitelist += value
           
            whitelist = set(whitelist)
    else:
        whitelist = set()
    
    print(whitelist)
    tree = ET.parse(input_net)
    root = tree.getroot()

    all_edges = root.findall('edge')
    traffic_edges = [e for e in all_edges if not e.get('id', '').startswith(':')]
    edges_to_remove = []

    # Progress bar for filtering step
    for edge in tqdm(traffic_edges, desc="Filtering low-speed residential edges"):
        edge_id = edge.get("id")
         # Skip if in whitelist
        if edge_id in whitelist:
            continue
        edge_type = edge.get('type')
        if edge_type != target_type:
            continue  # skip if not residential

        remove_edge = False
        for lane in edge.findall('lane'):
            speed_str = lane.get('speed')
            if speed_str is not None:
                try:
                    speed = float(speed_str)
                    if speed < speed_threshold:
                        remove_edge = True
                        break
                except ValueError:
                    pass

        if remove_edge:
            edges_to_remove.append(edge)

    print(f"\nEdges to remove: {len(edges_to_remove)}")

    # Progress bar for removal step
    for edge in tqdm(edges_to_remove, desc="Removing edges"):
        root.remove(edge)

    tree.write(output_net, encoding='utf-8', xml_declaration=True)
    print(f"\nFiltered network saved to {output_net}")
    print(f"Removed {len(edges_to_remove)} 'residential' edges under {speed_threshold} km/h.")

if __name__ == "__main__":
    if len(sys.argv) < 3 or len(sys.argv) > 4:
        print("Usage: python filter_low_speed_residential.py <input_net.xml> <output_net.xml> [whitelist.json or 'none']")
    else:
        whitelist = sys.argv[3] if len(sys.argv) == 4 else None
        filter_low_speed_residential_edges(sys.argv[1], sys.argv[2], whitelist)
