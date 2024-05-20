# -*- coding: utf-8 -*-
"""

@author: Juan Esteban Suarez Lopez, Zengxiang Lei

"""

from graph_reduction import *
from util import length_geometry

def main(input_folder, output_folder = 'ouptput/', cache_res=True, start_phase = 1, city = 'NYC'):
    '''
    This function makes the whole procedure for cleaning the join shape and return a file ready to be 
    cleaned for the simulator, this function requieres a base shape and a folder to put all the output shapes from
    the proccess, this input shape must be in WGS 84 coordinates, also it must contain at least the following
    fields:
        'SPEED':speed limit in mph
        'OneWay':direction field if FT: means that draw direction equal real direction of road, if TF:means
        that drawing direction is contrary to real road direction if none means bidirectional
        'FromZlev':number indicating the initial level  (z) of thew initial point of road in this case an integer
        'ToZlev':number indicating the final level (z) of thew initial point of road in this case an integer
        'Shape_Leng': length of road in m
        'st_width': width of the road (including all lanes) in feet
        'snow_pri': letter indicating priority of road can be any letter
        'bike_lane': field indicating if road has a bike lane
        
    ----------
    input_folder : str
        DESCRIPTION.
    output_folder : str, optional
        DESCRIPTION. The default is 'output'.
    elev: str, optional
        DESCRIPTION. Z-values of the map, ignore if not provided
    cache_res : boolean , optional
        DESCRIPTION. The default is False.

    Returns
    -------
    df7 : TYPE
        DESCRIPTION.

    '''

    import os

    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    # Loading shape
    # Phase 1: join 2 sources of info, this phase is moved to the speed calculation script
    # if(start_phase <= 1):
    #     shape1=input_folder + 'NYS_streets.gpkg' # state street, download from OpenStreetMap
    #     gdfNYS=gp.read_file(shape1)
    #     shape2=input_folder + 'NYC_streets.gpkg' # street, snow pri, bike lane, download from NYSDOT
    #     gdfNYC=gp.read_file(shape2)
        
    #     bcol=['osm_id', 'name']
    #     gdA=ckdnearest(gdfNYC, gdfNYS, bcol,1)
    #     if cache_res:
    #         gdA2=graph_togpk(gdA, input_folder + 'NYC_joined.gpkg')
    
    # Phase 2: fix the direction and generate intersection nodes
    if(start_phase <= 2):
        if(start_phase <= 2):
            name1 = input_folder + city +'_streets.gpkg'
            gdA = gp.read_file(name1)
        name2= input_folder + city +'_joined_corrected.gpkg'
        df2=correct_reduce(gdA) 
        df2=df2.drop(columns=['c_fin','c_ini','cords'])
        if cache_res:
            df2=graph_togpk(df2, name2)   
        print('phase 2 completed=add coordinates and nodes')
    
    
    # Phase 3: fix heights
    if(start_phase <= 3):
        if(start_phase == 3):
            name2= input_folder + city +'_joined_corrected.gpkg'
            df2 = gp.read_file(name2)
        df3=remove_heights(df2)
        name3=input_folder + city +'_joined_correctedbridges.gpkg'
        if cache_res:
            df3=graph_togpk(df3,name3)           
        print('phase 3 completed=bridges corrected')

    # Phase 4: combine/trim links based on degree 2 and alone links
    if(start_phase <= 4):
        if(start_phase == 4):
            name3=input_folder + city +'_joined_correctedbridges.gpkg'
            df3 = gp.read_file(name3)
        # repeat several times to clean all deadend links
        df4=reduc_shp_direc(df3)
        df4=reduc_shp_direc(df4)
        df4=reduc_shp_direc(df4)
        df4=reduc_shp_direc(df4)
        df4=reduc_shp_direc(df4)
        name4=input_folder+ city +'_joined_correctedbridges_red.gpkg'
        if cache_res:
            df4=graph_togpk(df4,name4)
        print('phase 4 completed=reduction of links')

    # Phase 5: add strong connectivity 
    if(start_phase <= 5):
        if(start_phase == 5):
            name4=input_folder+ city +'_joined_correctedbridges_red.gpkg'
            df4 = gp.read_file(name4)
        df5=add_ele_strong(df4)
        df5['elevation_i(ft)'] = 0
        df5['elevation_f(ft)'] = 0
        name5=input_folder+ city +'_joined_correctedbridges_red_strong.gpkg'
        if cache_res:
            df6=graph_togpk(df5,name5) 
            
        print('phase 5 complete=adding links to gain strong connectivity')

    # Phase 6: fix the 4 legs intersections
    if(start_phase <= 6):
        if(start_phase == 6):
            name5=input_folder+ city +'_joined_correctedbridges_red_strong.gpkg'
            df5 = gp.read_file(name5)
        df5['NUMBEROFLA']=np.round( df5['st_width']/12) # units are in feet
        df6=remove_5legs(df5,importance='NUMBEROFLA',dis_t='wgs')
        name6=input_folder+ city +'_joined_correctedbridges_red_4legs_strong.gpkg'
        if cache_res:
            df6=graph_togpk(df6,name6)
        print('phase 6 completed=fixing legs>4')
    
    # Phase 7: puts in the format for the simulator
    if(start_phase <= 7):
        if(start_phase == 7):
            name6=input_folder+ city +'_joined_correctedbridges_red_4legs_strong.gpkg'
            df6 = gp.read_file(name6)
            
        df7=transform_directed_to_lane(df6)
        name7=input_folder+ city +'_joined_correctedbridges_red_4legs_strong_sim.gpkg'
        if cache_res:
            df7=graph_togpk(df7,name7)
        print('phase 7 completed=putting final touches to version of simulator')
        
    # Phase 8: generate input files for the simulator, part 1
    if(start_phase <= 8):
        if(start_phase == 8):
            name7=input_folder+ city +'_joined_correctedbridges_red_4legs_strong_sim.gpkg'
            df7 = gp.read_file(name7)
        df8=get_road_shape(df7)
        name8=output_folder+'road_file'+ city +'.shp'
        df8=graph_togpk(df8,name8)
        df8_=df8[['linkID','nLane','Type','tLinkID','FN','TN','Left','Through','Right','Lane1','Lane2','Lane3','Lane4','Lane5','Lane6','Lane7','Lane8','Lane9','length']].copy()
        df8_.columns = ['LinkID','LaneNum','RoadType','TLinkID','FnJunction','TnJunction','Left','Through','Right','Lane1','Lane2','Lane3','Lane4','Lane5','Lane6','Lane7','Lane8','Lane9','Length']
        df8_.to_csv(output_folder+'/'+'road_file'+ city +'.csv', index = None)
        print('phase 8 completed=createing road shape')
    
    # Phase 9: generate input files for the simulator, part 2
    if(start_phase <= 9):
        if(start_phase == 9):
            name8=output_folder+'road_file'+ city +'.shp'
            df8 = gp.read_file(name8)
            if np.any(df8['length'] == 0): # the length inform is broken
                df8['length'] = df8['geometry'].apply(length_geometry)
                gpd_to_file(df8, output_folder+'road_file'+ city +'.shp')
                df8_=df8[['linkID','nLane','Type','tLinkID','FN','TN','Left','Through','Right','Lane1','Lane2','Lane3','Lane4','Lane5','Lane6','Lane7','Lane8','Lane9','length']].copy()
                df8_.columns = ['LinkID','LaneNum','RoadType','TLinkID','FnJunction','TnJunction','Left','Through','Right','Lane1','Lane2','Lane3','Lane4','Lane5','Lane6','Lane7','Lane8','Lane9','Length']
                df8_.to_csv(output_folder+'/'+'road_file'+ city +'.csv', index = None)
        df9=create_lane_shape(df8,val=0.00003)
        name9=output_folder+'lane_file'+ city +'.shp'
        df9=graph_togpk(df9,name9) 
        df9.drop(['geometry'], axis = 1).to_csv(output_folder+'/'+'lane_file'+ city +'.csv', index = None)
    
        print('phase 9 completed=creating lane shape')

if __name__ == '__main__':
    input_folder='input/'
    output_folder='output/'

    start_phase = input ("Enter the starting phase:")
    main(input_folder,output_folder, start_phase = int(start_phase))
