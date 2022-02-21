There are three types of files:

1. Charging planning results

"L_X_YYYYY.csv"
It contains the number of chargers in each zone when uncertainty level is X, 
and total daily charging demand is YYYYY kWh.

The first column: the region index, which has the same order as "NYC_zone_wgs84.shp" 
The second column: the number of planned chargers

2. Convergence results

"UB_LB_YYYYY.csv"
It contains the upper and lower bound of objective function when uncertainty level is X, 
and total daily charging demand is YYYYY kWh.

The first column: upper bound, unit: dollars/day.
The second column: lower bound, unit: dollars/day.

3. Visualization
"visualization_of_charging_planning_results.ppt"

