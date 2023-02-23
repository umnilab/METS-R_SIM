# -*- coding: utf-8 -*-
"""
@author: Jesua, Zengxiang Lei

"""

import pandas as pd
import geopandas
import numpy as np
from shapely.geometry import LineString, shape,Point
import shapely
from collections import Counter
from matplotlib import pyplot as plt
import networkx as nx
import geopandas as gp
import ast
import copy
from scipy.spatial import cKDTree
from operator import itemgetter
from itertools import chain
import math
import random
from tqdm import tqdm
from util import *

def shape_corrector(shape2=[]):
    '''
    This function takes a geodataframe and adds the coordinates if this shape is described by line strings,
    it adds initial and end point and also returns a description of the nodes of this shape

    Parameters
    ----------
    shape2 : geodataframe with geometry info
    Returns
    -------
    df2 : dataframe with added information of initial and end node, cords, initial and end cords of each line
    cords:dictionary of the node ID and it's coordenates'
    '''
  
    df2 = get_coords(shape2)
    df2['c_ini']=df2['cords'].apply(eval_list,ind=0)
    df2['c_fin']=df2['cords'].apply(eval_list,ind=-1)
    
    # generate index of initial and end nodes in 2D
    cords=df2['c_ini'].tolist()
    cords.extend(df2['c_fin'].tolist())
    x=list(Counter(cords).keys()) # equals to list(set(words))
    m1=np.array(list(map(str,df2['c_ini'])))       
    m2=np.array(list(map(str,df2['c_fin'])))                                                     
    l2=np.array(list(map(str,x)))
    l2=np.sort(l2)
    ind1=np.searchsorted(l2,m1)
    ind2=np.searchsorted(l2,m2)
    df2['nod_i']=ind1
    df2['nod_f']=ind2

    # deal with the duplicates, this may be casued by the fact that these links are in different heights
    new_node = max(df2['nod_i'].max(),df2['nod_f'].max()) + 1
    ## find links that have the same initial and end node
    visited = Counter(zip(df2['nod_i'],df2['nod_f']))
    to_process_links = [i for i in visited if visited[i]>1]

    # deal with self loops
    for j in df2[(df2['nod_i']==df2['nod_f'])&(df2['FromZlev']!=df2['ToZlev'])]['nod_i']:
        to_process_links.append((j, j))
    
    if 'index' in df2.columns:
        df2.drop('index',axis=1,inplace=True)
    df2 = df2.reset_index(drop=True)

    for link in to_process_links:
        ind = df2[(df2['nod_i']==link[0])&(df2['nod_f']==link[1])].index
        prev_ind = df2[df2['nod_f']==link[0]].index
        suc_ind = df2[df2['nod_i']==link[1]].index
        prev_ind = [i for i in prev_ind if i not in ind]
        suc_ind = [i for i in suc_ind if i not in ind]

        prev_height = {}
        suc_height = {}
        for i in prev_ind:
            if df2.loc[i,'ToZlev'] in prev_height:
                prev_height[df2.loc[i,'ToZlev']].append(i)
            else:
                prev_height[df2.loc[i,'ToZlev']] = [i]
        for i in suc_ind:
            if df2.loc[i,'FromZlev'] in suc_height:
                suc_height[df2.loc[i,'FromZlev']].append(i)
            else:
                suc_height[df2.loc[i,'FromZlev']] = [i]
        
        ## create new nodes based on the height of the link
        height = {}
        for i in ind:
            if df2.loc[i,'FromZlev'] in height:
                height[df2.loc[i,'FromZlev']].append(i)
            else:
                height[df2.loc[i,'FromZlev']] = [i]

        count = 0
        for key, value in height.items():
            count += 1
            if (count > 1):
                if key in prev_height:
                    for i in value:
                        df2.loc[i, 'nod_i'] = new_node
                    for i in prev_height[key]:
                        df2.loc[i, 'nod_f'] = new_node
                    new_node += 1
        
        height = {}
        for i in ind:
            if df2.loc[i,'ToZlev'] in height:
                height[df2.loc[i,'ToZlev']].append(i)
            else:
                height[df2.loc[i,'ToZlev']] = [i]

        count = 0
        for key, value in height.items():
            count += 1
            if (count > 1):
                if key in suc_height:
                    for i in value:
                        df2.loc[i, 'nod_f'] = new_node
                    for i in suc_height[key]:
                        df2.loc[i, 'nod_i'] = new_node
                    new_node += 1
    
    df2 = df2[df2['nod_i']!=df2['nod_f']]

    df2.drop_duplicates(subset=['nod_i','nod_f'],inplace=True, keep='first')

    df2['fid']=range(len(df2))
    return df2 
  
def connect_bridges(G,field):
    '''
    This function divides the network on intersections based on the field attribute of neighbors in a given network

    Parameters
    ----------
    G : undirected network x graph, should be connected
    field : string, with the name of the field to base the division

    Returns
    -------
    TYPE
        undirected nx graph, with new division

    '''
    G=G.copy()
    nodes=list(G.nodes)
    maxi=max(nodes)
    news=1
    for i in nodes:
        neig=list(G.neighbors(i))

        levels={}
        min_v=[]
        for j in neig:
            prop=G[i][j][field]
            props=G[i][j]
            if prop in levels:
                levels[prop].append([i,j])
                
            else:
                levels[prop]=[[i,j]]
        min_v=min([len(i) for i in levels.values()])
        if len(levels)>1 and min_v>1:
            for m in levels.values():
                new_node=maxi+news
                news+=1
                for n in m:
                    #print(n)
                    i,j=n  
                    props=G[i][j].copy()
                    a=props['nod_i']
                    b=props['nod_f']
                    if a==i:
                        a=new_node
                        props['nod_i']=a
                    else:
                        b=new_node
                        props['nod_f']=b
                    G.add_edge(a,b)
                    for key in props:
                        G[a][b][key]=props[key]
            G.remove_node(i)
    return G.copy()             

def reduc_shp_direc(df3):
    '''
    This function applies a reduction of the network combining when possible consecutive links
    and removing links which end at not connection

    Parameters
    ----------
    df2 : geopandas dataframe with the shape info, must have a nod_i and nod_f field
 
    -------
    df3:pandas dataframe with new graph
    graph2:networkx graph with new graph

    '''
    df2=df3.copy()
    df2 = get_coords(df2)
    df2['st_width_min']=0
    df2['st_width_mean']=0

    Graph1=nx.from_pandas_edgelist(df2.copy(), source='source', target='target', edge_attr=True, create_using=nx.DiGraph)
    graph2=Graph1.copy()
    
    removing_edges=[]
    removing_nodes=[]
    visited_nodes=[]

    co=0 # index of to-add edges
    la=[] # list of to-add edges, sub-edge belongs to the same link have the same index
  
    for i in list(Graph1.nodes):
        if not i in visited_nodes:
            co=co+1
            last_visit_node = None
            j=i
            cont=0
            cond1=True
            cond2=False
            while cond1:
                cont+=1
                visited_nodes.append(j)
                neg_pred=list(Graph1.predecessors(j))
                neg_suc=list(Graph1.successors(j))
                an_pred=len(neg_pred) # number of neighbors
                an_suc=len(neg_suc)

                if (an_pred==1 and an_suc==1 and len(set(neg_pred+neg_suc))==2): # nodes with degree 2 (for one-way) 
                    if cont==1: # first iteration
                        aneig1=list(Graph1.predecessors(neg_pred[0])) + list(Graph1.successors(neg_pred[0]))
                        aneig2=list(Graph1.successors(neg_suc[0])) + list(Graph1.predecessors(neg_suc[0]))
                        lan1=len(aneig1)
                        lan2=len(aneig2)

                        if lan1>2: # precedessor has more than 2 neighbors, search downstream
                            dir1 = 1                    
                            cond2=True # neighbor is a intersection
                            edg=Graph1[neg_pred[0]][j]
                            la.append([neg_pred[0],j,edg,cont-1,co])
                            edg=Graph1[j][neg_suc[0]]
                            la.append([j, neg_suc[0],edg,cont,co])
                            removing_edges.append((neg_pred[0], j))
                            removing_edges.append((j, neg_suc[0]))
                            j = neg_suc[0]
                        elif lan2>2: # successor has more than 2 neighbors, search upstream
                            dir1 = -1
                            cond2=True
                            edg=Graph1[j][neg_suc[0]]
                            la.append([j,neg_suc[0],edg,cont-1,co])
                            edg=Graph1[neg_pred[0]][j]
                            la.append([neg_pred[0],j,edg,cont,co])
                            removing_edges.append((neg_pred[0], j))
                            removing_edges.append((j, neg_suc[0]))
                            j = neg_pred[0]
                        elif lan1 == 1: # predecessor has no neighbors, indicating the end of the link, search downstream
                            removing_nodes.append(neg_pred[0])
                            dir1 = 1                    
                            cond2=True # neighbor is a intersection
                            edg=Graph1[neg_pred[0]][j]
                            la.append([neg_pred[0],j,edg,cont-1,co])
                            edg=Graph1[j][neg_suc[0]]
                            la.append([j, neg_suc[0],edg,cont,co])
                            removing_edges.append((neg_pred[0], j))
                            removing_edges.append((j, neg_suc[0]))
                            j = neg_suc[0]
                        elif lan2 == 1: # successor has no neighbors, indicating the end of the link, search upstream
                            removing_nodes.append(neg_suc[0])
                            dir1 = -1
                            cond2=True
                            edg=Graph1[j][neg_suc[0]]
                            la.append([j,neg_suc[0],edg,cont-1,co])
                            edg=Graph1[neg_pred[0]][j]
                            la.append([neg_pred[0],j,edg,cont,co])
                            removing_edges.append((neg_pred[0], j))
                            removing_edges.append((j, neg_suc[0]))
                            j = neg_pred[0]
                        else:
                            cond1=False
                    elif cond2: # cont > 1 and satisfies cond2, search along the same direction
                        if dir1 == 1:
                            edg=Graph1[j][neg_suc[0]]
                            la.append([j, neg_suc[0],edg,cont,co])
                            removing_edges.append((j, neg_suc[0]))
                            j = neg_suc[0]
                        elif dir1 == -1:
                            edg=Graph1[neg_pred[0]][j]
                            la.append([neg_pred[0],j,edg,cont,co])
                            removing_edges.append((neg_pred[0], j))
                            j = neg_pred[0]
                        else:
                            cond1 = False
                elif (an_pred == 2 and (set(neg_pred) == set(neg_suc))): # nodes with degree 4 and the same neighbors

                    if cont == 1: # first iteration
                        aneig1_pred=list(Graph1.predecessors(neg_pred[0])) 
                        aneig1_suc=list(Graph1.successors(neg_pred[0])) 
                        aneig2_pred=list(Graph1.predecessors(neg_pred[1]))
                        aneig2_suc=list(Graph1.successors(neg_pred[1]))
                        
                        lan1=len(aneig1_pred)
                        lan2=len(aneig2_pred)
                        if not (lan1 == 2 and (set(aneig1_pred) == set(aneig1_suc))): # the first neighbor is the end of this combination, search the other neighbor
                            dir1 = 2
                            cond2 = True
                            
                            edg=Graph1[neg_pred[0]][j]
                            la.append([neg_pred[0],j,edg,cont-1,co]) # positive when align with the searching direction
                            edg=Graph1[j][neg_pred[1]]
                            la.append([j, neg_pred[1],edg,cont,co])
                            removing_edges.append((neg_pred[0], j))
                            removing_edges.append((j, neg_pred[1]))

                            edg=Graph1[j][neg_pred[0]]
                            la.append([j, neg_pred[0],edg,cont-1,-co])
                            edg=Graph1[neg_pred[1]][j]
                            la.append([neg_pred[1],j,edg,cont,-co])
                            removing_edges.append((j, neg_pred[0]))
                            removing_edges.append((neg_pred[1],j))

                            last_visit_node = j
                            j = neg_pred[1]
                            
                        elif not (lan2 ==2 and set(aneig2_pred) == set(aneig2_suc)):
                            dir1 = 2
                            cond2 = True

                            edg=Graph1[j][neg_pred[1]]
                            la.append([j, neg_pred[1],edg,cont-1,-co])
                            edg=Graph1[neg_pred[0]][j]
                            la.append([neg_pred[0],j,edg,cont,-co]) # positive when align with the searching direction
                            
                            removing_edges.append((j, neg_pred[1]))
                            removing_edges.append((neg_pred[0], j))
                            
                            edg=Graph1[neg_pred[1]][j]
                            la.append([neg_pred[1],j,edg,cont-1,co])
                            edg=Graph1[j][neg_pred[0]]
                            la.append([j, neg_pred[0],edg,cont,co])
                            removing_edges.append((neg_pred[1], j))
                            removing_edges.append((j, neg_pred[0]))

                            last_visit_node = j
                            j = neg_pred[0]
                        else:
                            cond1 = False
                    elif cond2:
                        if dir1 == 2:
                            for neg in neg_pred:
                                if(neg == last_visit_node):
                                    continue
                                else:
                                    edg=Graph1[neg][j]
                                    la.append([neg,j,edg,cont,-co])
                                    removing_edges.append((neg, j))

                                    edg=Graph1[j][neg]
                                    la.append([j,neg,edg,cont,co])
                                    removing_edges.append((j, neg))

                                    last_visit_node = j
                                    j = neg
                                    break
                        else:
                            cond1 = False
                elif len(set(neg_pred+neg_suc))<=1 or len(neg_pred)==0 or len(neg_suc)==0: # dead end
                    cond1=False
                    removing_nodes.append(j)
                else:
                    cond1 = False                
    graph2.remove_edges_from(removing_edges)

    # add edges back
    la = sorted(la, key = lambda x: (x[-1], x[-2]))
    diri=la[0][-1]
    inds=[[]] # list of list
    for i in range(len(la)):
        dir2=la[i][-1]
        if diri==dir2:
            inds[-1].append(i)
        else:
            inds.append([i])
            diri=dir2

    for ind in inds:
        cords=[]
        widths=[]
        if(la[ind[0]][0] == la[ind[1]][1]):
            for j in ind[::-1]:
                # print(la[j][0:2])
                edg=la[j][2].copy()
                widths.append(edg['st_width'])
                cords.extend(edg['cords'])
            edg=copy.copy(edg)
            edg['cords']=clean_cords(cords)
            edg['st_width']=min(widths)

            ii=la[ind[-1]][0]
            jj=la[ind[0]][1]
            edg['nod_i']=ii
            edg['nod_f']=jj
            graph2.add_edge(ii,jj,**edg)
        else:
            for j in ind:
                # print(la[j][0:2])
                edg=la[j][2].copy()
                widths.append(edg['st_width'])
                cords.extend(edg['cords'])
            edg=copy.copy(edg)
            edg['cords']=clean_cords(cords)
            edg['st_width']=min(widths)

            ii=la[ind[0]][0]
            jj=la[ind[-1]][1]
            edg['nod_i']=ii
            edg['nod_f']=jj
            graph2.add_edge(ii,jj,**edg)

    graph2.remove_nodes_from(removing_nodes)


    graphs3 = list(graph2.subgraph(c) for c in nx.connected_components(graph2.to_undirected()))
    len_subg2=list(map(len,graphs3))
    in1=np.argmax(len_subg2)
    graphs3=graphs3[in1]

    df3=nx.to_pandas_edgelist(graphs3.copy())
    df3['geometry']=df3['cords'].apply(cords_geo)
    df3['length(m)']=df3['geometry'].apply(distance)
    if 'Shape_Leng(m)' in df3.columns:
        df3=df3.drop(columns=['Shape_Leng(m)'])
    gdf=gp.GeoDataFrame(df3)
    df_f=get_coords(gdf)
    df_f['source']=df_f['nod_i']
    df_f['target']=df_f['nod_f']    
    df_f['fid']=range(len(df_f))
    print(len(df_f))
    return df_f

def get_connectivity(df5):
    '''
    This function adds directed edges to df, to give it strong connectivity.
  

    Parameters
    ----------
    df: geopandas dataframe with geometry, source and target and cords properties
    Returns G, as directed and strongly connected graph
    -------
    None.

    '''
    df=df5.copy()
    df['Direction']=1
    # print(nx.is_connected(G1))
    G=nx.from_pandas_edgelist(df,'source','target',True,nx.DiGraph())
    if  not isinstance(G,nx.DiGraph):
        return 'G is not directed graph'
    else:
        G=G.copy()
        groups=list(nx.strongly_connected_components(G))
        # print('# groups='+str(len(groups)))
        edges=list(G.edges)
        nedg=G.number_of_edges()
        c=0
        nod_group={}
        for i in range(len(groups)):
            for ele in groups[i]:
                nod_group[ele]=i
        matrix={}

        pbar = tqdm(total = len(edges))
        for edg in edges:
            c+=1
            pbar.update(1)
            # print_p(c,nedg,tst='process 1=')

            ind1=nod_group[edg[0]]
            ind2=nod_group[edg[1]]
            k=(ind1,ind2)
            if not k in matrix.keys() and ind1!=ind2:
                matrix[k]=edg
        for i in matrix.values():
            prop=copy.copy(G[i[0]][i[1]])
            prop['Direction']=-1
            G.add_edge(i[1],i[0],**prop)
 
    print(nx.is_strongly_connected(G))
    df_f=nx.to_pandas_edgelist(G)
    df_f['nod_i']=df_f['source']
    df_f['nod_f']=df_f['target']
    ind=df_f.index[df_f['Direction']==-1].tolist()
    df_f.loc[ind,'cords']=df_f.loc[ind,'cords'].apply(twist)
    df_f.loc[ind,'geometry']=df_f.loc[ind,'cords'].apply(cords_geo)
    G1=nx.from_pandas_edgelist(df_f,'source','target',True,nx.DiGraph())
    G2=nx.from_pandas_edgelist(df_f,'nod_i','nod_f',True,nx.DiGraph())
    
    # print(nx.is_strongly_connected(G1))
    # print(nx.is_strongly_connected(G2))
    
    return df_f

def gpd_to_file(gpd,name='test'):
    '''
    This function revises the file and saves it as gpkg (geopandas)

    Parameters
    ----------
    gpd : geopandas dataframe
    name: name to export
    Returns
    -------
    gpd : geopandas dataframe

    '''
    gpd=gpd.copy()
    columns=gpd.columns
    i=gpd.index[0]
    deli=[]
    for j in columns:
        if (isinstance(gpd.loc[i,j],list) or isinstance(gpd.loc[i,j],tuple) or isinstance(gpd.loc[i,j],np.ndarray) ):
            deli.append(j)
    gpd=gpd.drop(columns=deli)
    gpd['fid']=range(len(gpd))
    if not '.gpkg' and not '.shp' in name:
        name=name.replace('.','')+'.gpkg'
    if '.gpkg' in name:
        gpd.to_file(name,driver='GPKG')
    if '.shp' in name:
        gpd.to_file(name,driver='ESRI Shapefile', crs='EPSG:4326')
    return gpd

def graph_togpk(G,name):
    '''
    This function transfrom a graph or dataframe to a geopandfas file and export it adding and removing data

    Parameters
    ----------
    G : graph or dataframe
    name : string og name to export
    Returns
    -------
    TYPE
        DESCRIPTION.

    '''
    if isinstance(G,nx.Graph):
        G=G.copy()
        df=nx.to_pandas_edgelist(G)  
    elif isinstance(G,pd.DataFrame) or isinstance(G,gp.GeoDataFrame):
        df=G.copy()
    else:
        return 'input must be dataframe or nx graph'
    if 'cords' in df.columns and 'geometry' not in df.columns:
        df['geometry']=df['cords'].apply(cords_geo)
    DFg=gp.GeoDataFrame(df)    
    DFg2=gpd_to_file(DFg,name)
    return DFg

def correct_direction(df,dirn='trafdir'):
    '''
    Add the inverse of the given link to the dataframe and changes the direction of some links based on 
    the dir field if dirn=FT does not nothing, if dirn=TF, changes the direction of the link
    if the dir=None then add another link in the opposite direction to current link

    Parameters
    ----------
    df : corrected shape with cords, geometry and nod_i nod_f
    dirn : TYPE, optional
        column where the direcxtion is located or 'TW' None means 2 way
        
    Returns
    -------
    None.

    '''
    df=df.copy()
    df['geometry']=df['geometry'].apply(get_linestring)
    df['cords']=df['geometry'].apply(get_coordinates)

    ind2=df.index[df[dirn]=='TF'].tolist()
    df.loc[ind2,'cords']=df.loc[ind2,'cords'].apply(twist)
    aux=df.loc[ind2,'FromZlev'].values
    df.loc[ind2,'FromZlev']=df.loc[ind2,'ToZlev']
    df.loc[ind2,'ToZlev']=aux

    df[df[dirn]=='TW']['st_width'] = df[df[dirn]=='TW']['st_width']/2 # split the road width for two-way roads
    df2=df[df[dirn]=='TW'].reset_index(drop=True)
    df2['cords']=df2['cords'].apply(twist)
    aux=df2['FromZlev'].values
    df2['FromZlev']=df2['ToZlev'].to_list()
    df2['ToZlev']=aux

    df_f=pd.concat([df,df2], axis = 0, ignore_index = True)
    df_f['geometry']=df_f['cords'].apply(cords_geo)
    df_f[dirn]='FT' #set all to FT
    return df_f
        
def correct_reduce(shape):
    '''
    This function corrects and prepare a given shape for reduction though combination and connectivity

    Parameters
    ----------
    shape : string: name of shape file to dead

    Returns
    -------
    df3 : dataframe with info of edge list

    '''
    # first correct the direction
    shape2=correct_direction(shape)
    if np.any(shape2['trafdir']!='FT'):
        print("Something goes wrong!")
        

    # then add the start and enc nodes of the links
    df2=shape_corrector(shape2)

    df2=df2.reset_index(drop=True)
    
    # selection of fields to export
    field=['fid','rw_type','st_label', 'st_name', 'trafdir', 'shape_leng', 'geometry', 'cords', 'c_ini', 'c_fin','nod_i','nod_f','st_width','snow_pri','bike_lane','FromZlev', 'ToZlev']
    check_f=[i in df2.columns for i in field]
    if not np.all(check_f):
        print("Warning: some data field is missing")

    # find the largest connected component and save it
    G=nx.from_pandas_edgelist(df2.copy(),source='nod_i',target='nod_f',edge_attr=field, create_using=nx.DiGraph)
    graphs2 = list(G.subgraph(c) for c in nx.connected_components(G.to_undirected()))
    len_subg2=list(map(len,graphs2))
    in1=np.argmax(len_subg2)
    G2=graphs2[in1]
    
    df3=nx.to_pandas_edgelist(G2)
    df3['source']=df3['nod_i']
    df3['target']=df3['nod_f']
    df3['or_fid']=df3['fid']
    df3['fid']=list(range(len(df3)))

    print(len(df3))

    return df3

def remove_heights(dfr):
    '''
    This function modifies the given shape based opon two from and to level which determines if a given link is
    connected in reality

    Parameters
    ----------
    df3 : TYPE
        DESCRIPTION.

    Returns
    -------
    df_f : TYPE
        DESCRIPTION. 

    '''
    df3=dfr.copy()
    
    G=nx.from_pandas_edgelist(df3,source='source',target='target',edge_attr=True,create_using=nx.DiGraph)
    G2=G.copy()
    new_node= max(df3['source'].max(),df3['target'].max()) + 1
    nodes=list(G.nodes)

    to_remove_edges = []

    # go through all intersections and check if there is a link that goes up and another that goes down
    for i in nodes:
        succ=list(G.successors(i))
        level_suc={}
        for j in succ:
            l=G[i][j]['FromZlev']
            if  l in level_suc.keys():
                level_suc[l].append(j)
            else:
                level_suc[l]=[j]
        pred=list(G.predecessors(i))
        level_prev = {}    
        for j in pred:
            l=G[j][i]['ToZlev']
            if  l in level_prev.keys():
                level_prev[l].append(j)
            else:
                level_prev[l]=[j]

        # match the from and to Z levels
        if len(level_prev)==1 and len(level_suc)>1:
            curr_lev = list(level_prev.keys())[0]
            for lev in level_suc:
                if lev!=curr_lev: # break the connection between i -> downward node since they are not connected
                    for j in level_suc[lev]:
                        to_remove_edges.append((i,j))
        elif len(level_prev)>1 and len(level_suc) == 1:
            curr_lev = list(level_suc.keys())[0]
            for lev in level_prev:
                if lev!=curr_lev: 
                    # break the connection between prev node -> i since they are not connected
                    for j in level_prev[lev]:
                        to_remove_edges.append((j,i))
        elif len(level_prev)>1 and len(level_suc)>1: # This suggests that this is a overpass or underpass
            count = 0
            for curr_lev in level_prev:
                for lev in level_suc:
                    if lev==curr_lev:
                        if count > 0: 
                            new_node += 1
                            for j1 in level_prev[curr_lev]:
                                for j2 in level_suc[lev]:
                                    edge=G[j1][i].copy()
                                    edge['nod_f']=new_node
                                    G2.add_edge(j1, new_node,**edge) 
                                    
                                    to_remove_edges.append((j1,i))

                                    edge2=G[i][j2].copy()
                                    edge2['nod_i']=new_node
                                    G2.add_edge(new_node,j2,**edge2)

                                    to_remove_edges.append((i,j2))
                        else:
                            count += 1
    
    G2.remove_edges_from(to_remove_edges)

    df_f = nx.to_pandas_edgelist(G2)
    df_f['source']=df_f['nod_i']
    df_f['target']=df_f['nod_f']
    df_f['fid']=range(len(df_f))

    return df_f

def add_ele_strong(df4,dirn='trafdir'):
    '''
    This function first adds opposite links based on dirn
    then it changes direction or adds directed links in some parts to achiegve strong connectiivty of the
    graph

    Parameters
    ----------
    df4: is a undirected shape with a field called OneWay which =FT if link directec or None otherwise
    

    Returns
    -------
    dirrected shape and shape of elevations

    '''
    df4=df4.copy()
    df4['cords']=df4['geometry'].apply(lambda x: list(x.coords))
    df_f=get_connectivity(df4) 
    df_f['fid']=range(len(df_f))
    df_f['or_fid']=df_f['fid']
    return df_f

def add_height_link(df,elev='elevation.gpkg'):
    '''
    This function add an elevation field of each link for the initial and end node

    Parameters
    ----------
    df : TYPE
        DESCRIPTION.
    elev : TYPE, optional
        DESCRIPTION. The default is 'elevation.gpkg'.

    Returns
    -------
    gdf : TYPE
        DESCRIPTION.

    '''
    df=df.copy()
    if isinstance(elev,str):        
        elevation=gp.read_file(elev)
    elevation=elevation.copy()
    gdf=gp.GeoDataFrame(df)
    gdf=get_coords(gdf)
    gdf['source']=gdf['nod_i']
    gdf['target']=gdf['nod_f']
    cords2=pd.DataFrame.from_dict(cords,orient='index',columns=['x','y'])
    cords2['cords_t']=list(cords.values())
    cords2['geometry']=cords2['cords_t'].apply(create)
    gdpoints=gp.GeoDataFrame(cords2).reset_index()
    gdpoints['fid']=gdpoints['index']
    gdf.plot()  
    #match elevation to cords
    gdA=ckdnearest(gdpoints, elevation, ['elevation'])
    gdA=gdA.drop(columns=['cords_t'])

    #matching elevation to edges
    ind1=gdf['nod_i'].values
    ind2=gdf['nod_f'].values
    gdf['elevation_i(ft)']=gdA.loc[ind1,'elevation'].values
    gdf['elevation_f(ft)']=gdA.loc[ind2,'elevation'].values
    return gdf,gdpoints

def transform_directed_to_lane(shape):
    '''
    This function takes a shape and puts it in the form of the road creator function which
    is an input of the simulator

    Parameters
    ----------
    shape : gp dataframe must have the field:['nod_i','nod_f','geometry','NUMBEROFLA','Direction',
    'elevation_i(ft)','or_fid','snow_pri']

    Returns
    -------
    gdf : TYPE
        DESCRIPTION.

    '''
    if isinstance(shape,str):
        gdf=gp.read_file(shape)
    elif isinstance(shape,gp.GeoDataFrame) or isinstance(shape,pd.DataFrame):
        gdf=shape.copy()

    G2=nx.from_pandas_edgelist(gdf,edge_attr=True,create_using=nx.DiGraph)
    
    df3=nx.to_pandas_edgelist(G2)
    gd3=gp.GeoDataFrame(df3)
    
    gd3['source']=gd3['nod_i']
    gd3['target']=gd3['nod_f']
    gd3['length(m)']=gd3['geometry'].apply(distance)
    dicr={'length(m)':'LENGTH(m)'}
    
    gd3=gd3.rename(columns=dicr)
    cols=['source','target','Opposite','rw_type','LENGTH(m)','NUMBEROFLA','elevation_i(ft)','elevation_f(ft)','geometry','or_fid','snow_pri']
    gdf=gd3.loc[:,cols]
    gdf['LENGTH(m)']= gdf['geometry'].apply(distance)

    gdf['FN']=gdf['source']
    gdf['TN']=gdf['target']
    gdf['nLane']=gdf['NUMBEROFLA']
    gdf = gdf.loc[:,~gdf.columns.duplicated()]
    return gdf

def remove_5legs(df,importance='nLane',dis_t='wgs',offset=15):
    '''
    
    This function takes a directed graph and removes 5 leg-intersections (more than 4 downstream edges)

    Parameters
    ----------
    df : TYPE
        DESCRIPTION.
    importance : TYPE, optional
        DESCRIPTION. The default is 'nLane'.
    offset : TYPE, optional
        DESCRIPTION. The default is 15.
    dis_t : TYPE, optional
        DESCRIPTION. The default is 'wgs'.

    Returns
    -------
    list
        DESCRIPTION.

    '''
    
    real=df.copy()
    real['Id']=range(0,len(real))
    vals=list(zip(*real['geometry'].apply(get_angles).to_list()))
    real['angi']=vals[0]
    real['angf']=vals[1]
    real['length(m)']= real['geometry'].apply(distance,args=(0,dis_t))
    real['nod_i']= real['source']
    real['nod_f']= real['target']
    if not 'virtual' in real.columns:
        real['virtual']=0   
    real['flipped']=0
    real['cords']=real['geometry'].apply(geo_to_cords)
    G=nx.from_pandas_edgelist(real,edge_attr=True,source='source',target='target', create_using=nx.DiGraph)
    # find opposite links
    real['Opposite'] = [G[nods[1]][nods[0]]['or_fid'] if G.has_edge(nods[1],nods[0]) else None for nods in zip(real['nod_i'], real['nod_f']) ]
    G2=nx.from_pandas_edgelist(real,edge_attr=True,source='source',target='target', create_using=nx.DiGraph)
    nodes=list(G2.nodes)
    new_node = max(nodes)
    new_edge = max(real['or_fid'])
    
    for i in nodes:
        if(i == 54558):
            print(list(G2.successors(i)))
            print(list(G2.predecessors(i)))
        for k in G2.predecessors(i):
            neig=Diff_l1l2(G2.successors(i), [k])  # list of successors
            if(i == 54558):
                print(k)
                print(list(G2.successors(i)))
                print(neig)
            if len(neig)>=4: # if more than 3 successors          
                ang=[]
                inds_or=[]
                lanes=[]
                edges=[]
                for j in neig:
                    edge=G2[i][j].copy()

                    ang.append(edge['angi'])
                    edges.append(edge)
                    
                    inds_or.append(j) 
                    lanes.append(edge[importance])
                    
                lanes_ind=np.argsort(-np.array(lanes)) # this is done to decide the 3 main roads
                new_node += 1
                new_edge += 1
                inter_link=edges[0].copy()
                inter_link['or_fid'] = new_edge
                inter_link['cords'] = edges[lanes_ind[3]]['cords'][0:2]
                inter_link['nod_f'] = new_node
                inter_link['Opposite'] = None
                # adjust the distance
                scale = offset/havesine(inter_link['cords'][0],inter_link['cords'][1])
                inter_link['cords'][1] = ([inter_link['cords'][0][0] + scale * (inter_link['cords'][1][0] - inter_link['cords'][0][0])
                                                ,inter_link['cords'][0][1] + scale * (inter_link['cords'][1][1] - inter_link['cords'][0][1])])

                inter_link['geometry']=LineString(inter_link['cords']) 
                G2.add_edge(i, new_node, **inter_link)

                for j in range(2,len(lanes_ind)):
                    ind_selected=lanes_ind[j] # actual index of road to be join to principal
                    new_link = edges[lanes_ind[j]].copy()
                    new_link['cords'] = [inter_link['cords'][1]] + new_link['cords'][1:]
                    new_link['nod_i'] = new_node
                    new_link['geometry']=LineString(new_link['cords']) 
                    G2.add_edge(new_node, inds_or[ind_selected], **new_link)
                    G2.remove_edge(i,inds_or[ind_selected]) # remove edge from graph
          
    
    df2=nx.to_pandas_edgelist(G2)
    df2=gp.GeoDataFrame(df2)

    df2['LENGTH(m)']= df2['geometry'].apply(distance)
    df2=df2.drop(columns=['length(m)'])
    df2['Id']=range(0,len(df2))
    vals=list(zip(*df2['geometry'].apply(get_angles).to_list()))
    df2['angi']=vals[0]
    df2['angf']=vals[1]
    df2['source']=df2['nod_i']
    df2['target']=df2['nod_f']   
    print('Resulting network is strongly connected?='+str(nx.is_strongly_connected(G2)))
    
    return df2

def get_road_shape(shape):
    '''
    This function generates the road shape necesarry for the simulator with all required data

    Parameters
    ----------
    shape : the shape must be in the desired form ( passing through all steps transform_shape)

    Returns
    -------
    df_f : TYPE
        DESCRIPTION.

    '''
    if isinstance(shape, str):
        df=gp.read_file(shape)
    else:
        df=shape
    df['LENGTH(m)']= df['geometry'].apply(length_geometry)
    fields=['Id','nLane','tLinkID','Type','FN','TN','Lane1','linkID','FREEFL01','length','geometry']
    df['def']=-1
    initial=df.copy()
    
    real=initial.loc[:,['or_fid','nLane','Opposite','rw_type','source','target','def','or_fid','def','LENGTH(m)','geometry']]
    real.columns=fields
    
    real2=real
    inds = real2['nLane']==0
    real2.loc[inds, 'nLane']=1
    inds = real2['nLane']>=9
    real2.loc[inds, 'nLane']=9
    vals=list(zip(*real2['geometry'].apply(get_angles).to_list()))
    real2['angi']=vals[0]
    real2['angf']=vals[1]
    real3=real2.copy().sort_values(by=['linkID']).reset_index(drop=True)
    real3['Id']=100000+np.arange(0,len(real3))
    real3['extra']=0
    
    for i in range(len(real3['tLinkID'])):
        a1=real3.loc[i,'tLinkID']
        if not np.isnan(a1):
            real3.loc[i,'extra']=real3[real3['linkID']==a1]['Id'].values[0]
    real3['tLinkID']=real3['extra']        
    real3['linkID']=real3['Id']
    
    real3['Left']=0
    real3['Through']=0
    real3['Right']=0
    t='Through'
    r='Right'
    l='Left'

    # real3.drop(['TN','FN'], axis = 1, inplace = True)

    max_lanes=int(real3['nLane'].max())
    
    cols=['Lane'+str(k) for k in range(1,max_lanes+1)]
    real3=real3.drop(columns=['Lane1'])
    for c in cols:
        real3[c]=0
    G=nx.from_pandas_edgelist(real3,edge_attr=True,source='FN',target='TN',create_using=nx.DiGraph)
    tetas=[45,180,240,315]

    import random
    linkid='linkID'
    number_lanes='nLane'
    
    nodes=list(G.nodes)
    
    for i in nodes:
       
        neig_r=list(G.successors(i))
        pred=list(G.predecessors(i))
        for j in pred:
        
            edge=G[j][i]
            # print(edge['tLinkID'])
            opposite_ind = real3[real3['linkID']==edge['tLinkID']]['TN'].values
            # print(j)
            # print(opposite_ind)

            angs=[]
            angi=edge['angf']
    
            lanes=list(range(edge[linkid]*10+1,int(edge[linkid]*10+edge[number_lanes])+1))
            cols=['Lane'+str(k) for k in range(1,int(edge[number_lanes])+1)]
            for c in range(len(cols)):
                edge[cols[c]]=lanes[c]
                
            neig=Diff_l1l2(neig_r,opposite_ind)
            for z in neig:
                angs.append(G[i][z]['angi'])
            relative_ang=transform_angles(np.array(angs)-angi-tetas[-1])

            if len(neig)==1:
                edge[t]=G[i][neig[0]][linkid]
            
            elif len(neig)==2:
                ind_t=np.argsort(relative_ang)
                edge[t]=G[i][neig[ind_t[0]]][linkid]
                
                ind_v=def_dir_ang([relative_ang[ind_t[-1]]],[0,180,240,360])
                edge[ind_v[0]]=G[i][neig[ind_t[-1]]][linkid]
            elif len(neig)==3:
                ind_t=np.argsort(relative_ang)
                edge[t]=G[i][neig[ind_t[0]]][linkid]
                edge[l]=G[i][neig[ind_t[1]]][linkid]
                edge[r]=G[i][neig[ind_t[2]]][linkid]
            elif len(neig)>3:
                print('Warning: intersection with too many neighbors in i='+str(i))
                ind_t=np.argsort(relative_ang)
                edge[t]=G[i][neig[ind_t[0]]][linkid]
                edge[l]=G[i][neig[ind_t[1]]][linkid]
                edge[r]=G[i][neig[ind_t[2]]][linkid]
       
    
    dfff=nx.to_pandas_edgelist(G)
    dfff['FN'] = dfff['source']
    dfff['TN'] = dfff['target']
    dfff=dfff.loc[:,real3.columns]
    la=dfff.loc[:,[r,l,t]].sum(axis=1)
    # dfff = dfff[la!=0].reset_index(drop=True)
    print(dfff[la==0][['FN','TN']].values)
    print('elements with no neighbors='+str(sum(la==0)))
    cols_r=[ 'FREEFL01','angi', 'angf', 'extra']
    df_f=dfff.drop(columns=cols_r)
    return df_f

def match_lanes(own_lanes,lanes):
    '''
    This function takes a set of lanes belong to a road and matches them to a series of lanes
    of all the other roads this road connects to. 

    Parameters
    ----------
    own_lanes : list of lane ID 
    lanes : Disctionary of lanes belonging to each target road 

    Returns
    -------
    unions : list of tuples with the pairs of joins beetween lane IDS

    '''
    initial_lanes=copy.copy(own_lanes)
    dic_neigh=copy.copy(lanes)
    lens=[len(i) for i in dic_neigh.values()]
    l_m=initial_lanes[0]
    r_m=initial_lanes[-1]
    if len(initial_lanes)<=2:
        central=initial_lanes
    else: 
        central=Diff_l1l2(initial_lanes, [l_m])
        central=Diff_l1l2(central, [r_m])
    unions={}
    for i in dic_neigh.keys():
        if i[1]=='Right':
            if r_m in unions:
                unions[r_m].append([dic_neigh[i][-1],'Right'])
            else:
                unions[r_m]=[[dic_neigh[i][-1],'Right']]
        elif i[1]=='Left':
            if l_m in unions:
                unions[l_m].append([dic_neigh[i][0],'Left'])
            else:
                unions[l_m]=[[dic_neigh[i][0],'Left']]
        else:
            m=min([len(central),len(dic_neigh[i])])
            for n in range(m):
                if central[n] in unions.keys():
                    unions[central[n]].append([dic_neigh[i][n],'Through'])
                else:
                    unions[central[n]]=[[dic_neigh[i][n],'Through']]



    return unions

def create_lane_shape(road,val=0.00003):
    '''
    This function adds lanes to a road shape and the  connectivity info of them
    This lane shape has the fields ['ID','Link','Left','Through','Right','laneID','length','geometry']
    the fields left through and right indicates if the lane this lane connect to is a left,right or
    through turn

    Parameters
    ----------
    road : road shape as geopandas dataframe
    val : distance, in this casde the value correponds to approximate 3.4 meters ( in wgs 84 coords)

    Returns
    -------
    df_f2 : lane shape geodataframe

    '''
    cols2=['LaneID','LinkID','Left','Through','Right','Length(m)','geometry']
    df_f=road.copy()
    linkid='linkID'
    t='Through'
    r='Right'
    l='Left'
    number_lanes='nLane'
    df_f['nod_i']=df_f['FN']
    df_f['nod_f']=df_f['TN']

    df_f=df_f.set_index(linkid)
    edgs=list(df_f.index) # list of link IDs
    lane_count=1 # counter of lanes
    result=[]
    adv=-1

    pbar = tqdm(total = len(edgs))
    for i in edgs:
        adv+=1
        pbar.update(1)
        edge=df_f.loc[i,:] # get the i-th edge

        cols=['Lane'+str(k) for k in range(1,int(edge[number_lanes])+1)] # get the cols of the lanes
        own_lanes=edge[cols].to_list() # get the lanes of the edge
        lanes={}
        for m in [r,t,l]: # get the road ids of the neighbors
            neig=df_f.loc[i,m]
            if neig!=0: # if the neighbor is not empty
                edge2=df_f.loc[neig,:] # get the neighbor edge
                cols=['Lane'+str(k) for k in range(1,int(edge2[number_lanes])+1)] # get the cols of the lanes
                lanes[(neig,m)]=edge2[cols].to_list() # get the lanes of the neighbor edge
        unions=match_lanes(own_lanes,lanes)
        for k in own_lanes:
            if not k in unions:
                unions[k]=[]
        count=-1
        offset=np.arange(val/2,val*(len(unions)+1/2),val)
        
        for ll in sorted(unions): # need the key to be sorted, which is default for Python 3.7+
            count+=1
            lane_count+=1
            dic_p={t:0,r:0,l:0}
            for c in unions[ll]:
                dic_p[c[1]]=c[0]
            geom_i=edge['geometry']
            geo2=LineString(paralel_off(geom_i,offset[count]))
            dis=distance(geo2)
            if(dis>20000): # for debugging
                print("Something went wrong")
                print(distance(geom_i))
                print(dis)
                print(offset[count])
            result.append([ll,i,dic_p[l],dic_p[t],dic_p[r],dis,geo2])
            
    df_f2=pd.DataFrame(result,columns=cols2)
    return df_f2

