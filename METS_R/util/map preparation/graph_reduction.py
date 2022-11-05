# -*- coding: utf-8 -*-
"""
Created on Sun Mar 17 14:30:35 2019

@author: jesua

Revised by Zengxiang Lei (lei67@purdue.edu) on Oct 12 2022
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

def create_points(cords):
    '''
    This functions takes a list of coordinates and transform it to a series of shapely points

    Parameters
    ----------
    cords : list of cords

    Returns
    -------
    points : list of shapely points.

    '''
    points=[]
    for i in cords:
        points.append(Point(i[0],i[1]))
    return points

def paralel_off(geo1,off):
    '''
    This function takes a linestring and thenapplies and offset to it, to the right or left of 
    it's direction.

    Parameters
    ----------
    geo1 : Linestring object
    off : number describing the amount to be displaiced if number is positive the displacement is to
    the right, if negative it will be displaced to the left

    Returns
    -------
    linsetring object displaced

    '''
    geom=copy.copy(geo1)
    if isinstance(geom,shapely.geometry.LineString):
        cords=geo_to_cords(geom)
    elif isinstance(geom,list) or isinstance(geom,np.ndarray):
        cords=geom
    else:
        print('error')
        return geom
    l=len(cords)
    new_segs=[]
    for i in range(l-1):
        seg=np.array([cords[i],cords[i+1]])
        dire=(seg[1]-seg[0])
        dire=dire/np.linalg.norm(dire)
        dire_n=np.array([dire[1],-dire[0]])
        seg_norm=seg+off*dire_n
        new_segs.append(seg_norm)
    s=len(new_segs)
    new_points=[new_segs[0][0]]
    for i in range(s-1):
        s1=new_segs[i]
        s2=new_segs[i+1]
        p1=s1[0]
        dir1=s1[1]-s1[0]
        p2=s2[0]
        dir2=s2[1]-s2[0]
    
        p=inter(dir1,p1,dir2,p2)
        new_points.append(list(p))
    new_points.append(new_segs[-1][-1])
    cords=np.array(cords)
    new_points=np.array(new_points)
   #  fig, axs = plt.subplots(1, 1)
   #  axs.axis('equal')
   #  axs.plot(cords[:,0],cords[:,1])
   # # plt.plot(new_points[:,0],new_points[:,1])
   #  for i in range(s):
   #      seg=new_segs[i]
   #      axs.plot(seg[:,0],seg[:,1],'r')
   #  axs.plot(new_points[:,0],new_points[:,1])
    return new_points
      
def inter(dir1,p1,dir2,p2):
    
    
    '''
    This function finds the intersection of two lines defined by it';s direction and a point'

    Parameters
    ----------
    dir1 : list of coordinates of direction vector of line 1
    p1 : list of coordinates point 1
    dir2 :list of coordinates of direction vector of line 2
    p2 : list of coordinates point 2

    Returns
    -------
    pp : coordinates of intersection point

    '''
    dir1=np.array(dir1)/np.linalg.norm(dir1)
    dir2=np.array(dir2)/np.linalg.norm(dir2)
    p1=np.array(p1)
    p2=np.array(p2)
    A=np.array([dir1,-dir2]).T
    B=p2-p1
    if all(dir1==dir2):
        pp=p2
    else:
        p=np.linalg.solve(A,B)
        pp=p1+p[0]*dir1
    return pp

def min_ang(a,b):
    '''
    Calculates min ang beetween 2 angles given as (0-360 degrees)

    Parameters
    ----------
    a : float angle in degrees 
    b : float angle in degrees

    Returns
    -------
    ang : min angles in degrees

    '''

    if a<0:
        a=np.abs(a)%360
        a=(360-a)
    if b<0:
        b=np.abs(b)%360
        b=(360-b)
    a=a%360
    b=b%360
    ang=a-b
    if np.abs(a-b)>180:
        ang=360-np.abs(a-b)
    return np.abs(ang)

def n_neigh(lis,i,n=1):
    '''
    Get neighbors of list in a given point

    Parameters
    ----------
    lis : list
    n : number of neighbors
    i=pos
    Returns
    -------
    nei : list of lists with neighbs of each point in the array

    '''

    nn=int(n/len(lis))
    mm=n%len(lis)
    neigs1=i-np.arange(1,mm+1)
    neigs2=i+np.arange(1,mm+1)
    neigs2=np.mod(np.abs(neigs2),len(lis))
    
    neigs1=list(neigs1)+nn*list(range(-1,-len(lis),-1))
    neigs2=list(neigs2)+nn*list(range(1,len(lis)-1))
    l1=itemgetter(*neigs1)(lis)
    l2=itemgetter(*neigs2)(lis)
    if n==1:
        l1=[l1]
        l2=[l2]
    nei=list(chain(l1,l2))
    return nei

def interpret(df,j):
    '''
    this function interprests a string representation of a dataframe column of lists of cords and transform it to geometry

    Parameters
    ----------
    df : pandas dataframe
    j : column name where string of stored cords is stored

    Returns
    -------
    df : pandas dataframe with added geometry
    '''
    la=[]
    leq=[]
    for i in df.index:
        cords=ast.literal_eval(df.loc[i,j])
        la.append(cords)
        leq.append(LineString(cords))
    df[j]=la
    df['geometry']=leq
    
    return df

def getPRJwkt(epsg):
   """
   Grab an WKT version of an EPSG code
   usage getPRJwkt(4326)

   This makes use of links like 
   http://spatialreference.org/ref/epsg/4326/prettywkt/
   """

   import urllib
   sr = "http://spatialreference.org"
   f=urllib.request.urlopen(sr + "/ref/epsg/{0}/prettywkt/".format(epsg))
   n2=f.read()
   return str(n2)[2::]

def twist(a):
    '''
    return the a lsit flipped

    Parameters
    ----------
    a : list

    Returns
    -------
    flipped list
        

    '''
    return a[::-1]

def get_coordinates(a):
    
    l=list(a.coords)
    return l

def cords_geo(a):
    return LineString(a)

def eval_list(a,ind):
    return list(a)[ind]

def typeg(a):
    return a.geom_type

def havesine(p1,p2):
    '''
    calculates distance of two points in WGS 84 coordinates

    Parameters
    ----------
    p1 : list or touple 1, x,y
    p2 : list or touple 1, x,y

    Returns distance in meters
    -------
    TYPE
        DESCRIPTION.

    '''
    R = 6373000
    p1=np.array(p1)
    p2=np.array(p2)
    lat1 = np.radians(p1[0])
    lon1 =np.radians(p1[1])
    lat2 = np.radians(p2[0])
    lon2 = np.radians(p2[1])
    dlon = lon2 - lon1
    dlat = lat2 - lat1
    a = np.sin(dlat / 2)**2 + np.cos(lat1) * np.cos(lat2) * np.sin(dlon / 2)**2
    c = 2 * np.arctan2(np.sqrt(a), np.sqrt(1 - a))
    return  R * c

def proy_dis(p1,p2):
    p1=np.array(p1)
    p2=np.array(p2)
    print('proy')
    return np.linalg.norm(p1-p2)

def distance(cords,par=0,opt='wgs'):
    '''
    calculates length of a set of cords in wgs 84 coordinates

    Parameters
    ----------
    cords : list of cords or shapely object with cords of points in wgs 84
    par : TYPE, optional
        option if 0 jsut return length of line, otherwise will return len and length ofg each lkinear segment

    Returns
    -------
    TYPE
        DESCRIPTION.

    '''
    if opt=='proy':

        dis_f=proy_dis
        
    else:
        dis_f=havesine
        
    if isinstance(cords,shapely.geometry.LineString) or isinstance(cords,shapely.geometry.MultiLineString):
        cords=geo_to_cords(cords)
    
    if isinstance(cords,str):
        cords=ast.literal_eval(cords)
    if any([isinstance(c,list) for c in cords]):
        sup_cords=copy.copy(cords)
        dis=0
        distancs=[]
        for cords in sup_cords:
            
            for i in range(len(cords)-1):
                cord1=cords[i]
                cord2=cords[i+1]
                p1=np.array(cord1[::-1])
                p2=np.array(cord2[::-1])
                newd=dis_f(p1,p2)
                dis=dis+newd
                distancs.append(newd)
    else:
        
        dis=0
        distancs=[]
        for i in range(len(cords)-1):
            cord1=cords[i]
            cord2=cords[i+1]
            p1=np.array(cord1[::-1])
            p2=np.array(cord2[::-1])
            newd=dis_f(p1,p2)
            dis=dis+newd
            distancs.append(newd)
            

    if par==0:    
        return dis
    else:
        return dis,distancs
    
def divide_line(line,dist,dis_t='wgs'):
    '''
    This function gets the position of a point located after a given distance from a line, also splits the
    line in two parts from initial point to the created point and from this point to the end

    Parameters
    ----------
    line : list of coordinates of the line in wgs 84,
    dist : distance in meters to get a point 
    dist_t: text indicating type of distance to use if 'proy' then calculate euclidean distance if 'wgs' calculates havesine
    Returns
    dictionary with cords of the point and the new two divions of the line
    -------
    None.

    '''

    if not isinstance(line,list):
        cords=geo_to_cords(line)
    else:
        cords=line

    dis,distances=distance(cords,1,dis_t)
    cumdis=np.cumsum(distances)
    
    i=np.where(cumdis<=dist)
    
    if dist<dis:
        if len(i[0])>0:
            i=i[0][-1]
            p1=np.array(cords[i+1])
            p2=np.array(cords[i+2])
            alf=(dist-cumdis[i])/distances[i+1]
        else:
            i=-1
            p1=np.array(cords[0])
            p2=np.array(cords[1])
            alf=(dist)/distances[0]
        new_point=p1+alf*(p2-p1)
        new_point=tuple(new_point)
        cords1=cords[0:i+2]+[new_point]
        cords2=[new_point]+cords[i+2::]  
    else:
        i=len(cumdis)
        p1=np.array(cords[-2])
        p2=np.array(cords[-1])
        alf=(dist-cumdis[-1])/distances[-1]
        new_point=p2+alf*(p2-p1)
        new_point=tuple(new_point)
        cords1=cords
        cords2=[cords[-1],new_point]
    return {'point':new_point,'line1':cords1,'line2':cords2}

def create(poin):
    return Point(poin)
    
def clean_cords(cords):
    '''
    this functions takes a set of coordinates and remove contiguos duplicates of coordinates

    Parameters
    ----------
    cords : list of  tuples of cords
    Returns
    -------
    r :vector of coordinates preserving order
    '''
    r=[]
    r.append(cords[0])
    for i in range(1,len(cords)):
        if cords[i]!=cords[i-1]:
            r.append(cords[i])
    return r

def length_geometry(geom):
    
    '''
    This function calculaytes length of a given geometry supposing the specified order of the points

    Parameters
    ----------
    geom :shapely linestring, with points in cords WGS 84

    Returns
    -------
    TYPE
        distance in meters

    '''
    cords=geo_to_cords(geom)
    return distance(cords)

def get_angles(geom):
    if  isinstance(geom,list) :
        cords=geom
    else:
        cords=geo_to_cords(geom)
        
    p1=cords[0]
    p2=cords[1]
    ang1=math.atan2(p2[1]-p1[1],p2[0]-p1[0])*180/np.pi
    if ang1<0:
        ang1=360+ang1
    p1=cords[-2]
    p2=cords[-1]
    ang2=math.atan2(p2[1]-p1[1],p2[0]-p1[0])*180/np.pi
    if ang2<0:
        ang2=360+ang2
    return [ang1,ang2]

def Diff_l1l2(li1,li2):
    '''
    Returns elements from l1 that are not in l2

    Parameters
    ----------
    l1 : TYPE
        DESCRIPTION.
    l2 : TYPE
        DESCRIPTION.

    Returns
    -------
    TYPE
        DESCRIPTION.

    '''
    return (list(set(li1) - set(li2))) 
  
def Diff(li1, li2): 
    '''
    return elements that are not in common beetween 2 lists
    Parameters
    ----------
    li1 : list1
    li2 : list2
    Returns
    list
    '''
    if li1==li2:
        return []
    else:
        return (list(set(li1+li2) - set(intersection(li1,li2))))   
def intersection(lst1, lst2): 
    return list(set(lst1) & set(lst2)) 
def reverse_geo(geo):
    cords=geo_to_cords(geo)
    cords=cords[::-1]
    return cords_geo(cords)
def get_linestring(typ):
    if isinstance(typ,shapely.geometry.MultiLineString):
        return typ[0]
    else:
       return typ

def shape_corrector(shape2=[]):
    '''
    This function takes a geodataframe and adds the coordinates if this shape is described by line strings
    addsa initial and end point and also returns a description of the nodes of this shaoe

    Parameters
    ----------
    shape2 : geodataframe with geometry info
    Returns
    -------
    df2 : dataframe with added information of initial and end node, cords, initial and end cords of each line
    cords:dictionary of the node ID and it's coordenates'
    '''
  
    df2=shape2.copy()
    
    #df2=df2[df2['SNOW_PRI'].isin(['V'])]
    df2['geometry']=df2['geometry'].apply(get_linestring)
    
    df2['cords']=df2['geometry'].apply(get_coordinates)
    df2['c_ini']=df2['cords'].apply(eval_list,ind=0)
    df2['c_fin']=df2['cords'].apply(eval_list,ind=-1)
    
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
    df2=df2.drop_duplicates(subset=['nod_i','nod_f'], keep='first')
    l3=list(map(ast.literal_eval,l2))
    cords= dict(zip(list(range(len(l3))), l3)) 
    df2=df2[df2['nod_i']!=df2['nod_f']]
    df2['fid']=range(len(df2))
    return df2,cords  
  
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
    This function applies a reduction of the network combining when possible consecutive links with same direction
    and remocinv links which end at not connection

    Parameters
    ----------
    df2 : geopandas dataframe with the shape info, must have a nod_i and nod_f field
 
    -------
    df3:pandas dataframe with new graph
    graph2:networkx graph with new graph

    '''
    df2=df3.copy()
    df2['st_width_min']=0
    df2['st_width_mean']=0
    df2['length(m)']=df2['geometry'].apply(distance)
    namd='Direction'
    df2[namd]=df2['OneWay'].replace(['FT', None],[1,0])

    Graph1=nx.from_pandas_edgelist(df2.copy(), source='source', target='target', edge_attr=True)
    graph2=Graph1.copy()
    removing_nodes=[]

    numnod=Graph1.number_of_nodes()
    co=0
  
    for i in list(Graph1.nodes):
        co=co+1
      
        if not i in removing_nodes:
               
                j=i
                cont=0
                cond1=True
                cond2=False

                la=[]
                while cond1:
                    cont+=1
              
                    aneig=list(Graph1.neighbors(j))
                    an=len(aneig)
                    if an==2:
                        
                        aneig1=list(Graph1.neighbors(aneig[0]))
                        aneig2=list(Graph1.neighbors(aneig[1]))
                        lan1=len(aneig1)
                        lan2=len(aneig2)
                        if cont==1:
                            if lan1>2:
                                j_ant=aneig[0]
                                j_n=aneig[1]                        
                                cond2=True
                            elif lan2>2:
                                j_ant=aneig[1]
                                j_n=aneig[0]
                                cond2=True
                                
                            if lan1>2 or lan2>2:
                                edg=Graph1[j][j_ant]
                                ant=edg['nod_i']
                                if j_ant==ant:
                                    dir1=+1
                                else:
                                    dir1=-1
                                la.append([j_ant,j,ant,dir1,edg[namd],edg])
                                j_ant=j
                                removing_nodes.append(j)
                                graph2.remove_node(j)
                                j=j_n
                            else:
                                cond1=False
                               
                        elif cond2:
                            edg=Graph1[j_ant][j]
                            ant=edg['nod_i']
                            if j_ant==ant:
                                dir1=+1
                            else:
                                dir1=-1
                            la.append([j_ant,j,ant,dir1,edg[namd],edg])
                            j_n=Diff(aneig,[j_ant])[0]
                            j_ant=j 
                            removing_nodes.append(j)
                            graph2.remove_node(j)
                            j=j_n
                            
                           
                    elif cont>1 and cond2 and an>2:
                        cond1=False
                        edg=Graph1[j_ant][j]
                        ant=edg['nod_i']
                        if j_ant==ant:
                            dir1=+1
                        else:
                            dir1=-1
                        la.append([j_ant,j,ant,dir1,edg[namd],edg])
                    elif an==1 and len(list(Graph1.neighbors(aneig[0])))>2:
                        
                        cond1=False
                        removing_nodes.append(j)
                        graph2.remove_node(j)
                    elif an==1 and cont>1:
                        cond2=False
                        cond1=False
                        removing_nodes.append(j)
                        graph2.remove_node(j)
                    else:
                        cond1=False
                      
                    #print('j_ant='+str(j_ant)+'  j='+str(j))
                    
                if cont>1 and len(la)>0 and cond2:
                    diri=la[0][3]*la[0][4]
                    inds=[[]]
                    for i in range(len(la)):
                        dir2=la[i][3]*la[i][4]
                        if diri==dir2:
                            inds[-1].append(i)
                        else:
                            inds.append([i])
                            diri=dir2
       
                    
                    for i in inds:
                        cords=[]
                        widths=[]
                        lens=[]
                        for j in i:
                            edg=la[j][5].copy()
                            widths.append(edg['st_width'])
                            lens.append(edg['length(m)'])
                            if la[j][3]==-1:
                                cords.extend(twist(edg['cords']))
                            else:
                                
                                cords.extend(edg['cords'])
                 
                        edg=copy.copy(edg)
                        edg['cords']=clean_cords(cords)
                        edg['st_width_min']=min(widths)
                        edg['st_width_mean']=sum(np.array(widths)*np.array(lens))/sum(lens)

                        ii=la[i[0]][0]
                        jj=la[i[-1]][1]
                        dirnn=la[i[0]][3]*la[i[0]][4]
                        if dirnn<0:
                            aux=ii
                            ii=jj
                            jj=aux
                            edg['cords']=clean_cords(twist(cords))
                        edg['nod_i']=ii
                        edg['nod_f']=jj
                        graph2.add_edge(ii,jj,**edg)



    df3=nx.to_pandas_edgelist(graph2.copy())
    df3['geometry']=df3['cords'].apply(cords_geo)
    df3['length(m)']=df3['geometry'].apply(distance)
    if 'Shape_Leng(m)' in df3.columns:
        df3=df3.drop(columns=['Shape_Leng(m)'])
    gdf=gp.GeoDataFrame(df3)
    answ=shape_corrector(gdf)
    df_f=answ[0].copy()
    df_f['source']=df_f['nod_i']
    df_f['target']=df_f['nod_f']    
    ind1=df_f['st_width_min']==0
    ind2=df_f['st_width_mean']==0
    df_f.loc[ind1, 'st_width_min']=df_f.loc[ind1,'st_width']
    df_f.loc[ind2,'st_width_mean']=df_f.loc[ind2,'st_width']
    df_f['st_width']=df_f['st_width_mean']
    return df_f, graph2

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
    G1=nx.from_pandas_edgelist(df,'source','target',True)
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

 
        # print(nx.is_strongly_connected(G))
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

def test_funcs(nam='test_draw.csv'):
    '''
    test of the functions

    Returns
    -------
    None.

    '''
    plt.close('all')
    df2=pd.read_csv(nam)
    l1=[[(i+j,i+j) for i in range(2)] for j in range(len(df2))]
    df2['cords']=l1
    G1=nx.from_pandas_edgelist(df2,target='nod_f',source='nod_i',edge_attr=True,create_using=nx.DiGraph())
    
    if nam=='test_draw.csv':
        df=pd.read_csv('pos.csv')
        dic={}
        for i in df.index:
            dic[df.loc[i,'nod']]=df.loc[i,['x','y']].to_list()
        plt.figure()
        nx.draw_networkx(G1,pos=dic)
        df2['source']=df2['nod_i']
        df2['target']=df2['nod_f']
        df3, graph2=reduc_shp_direc(df2)
        plt.figure()
        nx.draw_networkx(graph2,pos=dic)
        
        G2=get_connectivity(graph2)
        plt.figure()
        nx.draw_networkx(G2,pos=dic)
    else:
        plt.figure()
        nx.draw_networkx(G1)
        df3, graph2=reduc_shp_direc(df2)
        plt.figure()
        nx.draw_networkx(graph2) 
        G2=get_connectivity(graph2)
        plt.figure()
        nx.draw_networkx(G2)
    return True

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
    gpd.to_file(name,driver='GPKG')
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

def correct_direction(df,dirn='OneWay',opt=0):
    '''
    Add the inverse of the given link to the dataframe and changes the direction of some links based on 
    the dir field if dirn=FT does not nothing, if dirn=TF, changes the direction of the link
    if the dir=None then add another link in the opposite direction to current link

    Parameters
    ----------
    df : corrected shape with cords, geometry and nod_i nod_f
    dirn : TYPE, optional
        column where the direcxtion is located None means 2 way
        
    Returns
    -------
    None.

    '''
    df=df.copy()
    df['change_dir']=0
    if opt==0:
        ind2=df.index[df[dirn]=='TF'].tolist()
        df.loc[ind2, 'change_dir']=1
        df.loc[ind2, dirn]='FT'
        df.loc[ind2,'cords']=df.loc[ind2,'cords'].apply(twist)
        df['geometry']=df['cords'].apply(cords_geo)
        aux=df.loc[ind2,'nod_i']
        df.loc[ind2,'nod_i']=df.loc[ind2,'nod_f']
        df.loc[ind2,'nod_f']=aux
        df_f=df
    else:
        ind2=df.index[df[dirn]=='TF'].tolist()
        df.loc[ind2, dirn]='FT'
        df.loc[ind2, 'change_dir']=1
        
        if 'cords' in df.columns:
            df['geometry']=df['cords'].apply(cords_geo)
        else:
            df['cords']=df['geometry'].apply(geo_to_cords)
            
        df.loc[ind2,'cords']=df.loc[ind2,'cords'].apply(twist)
        aux=df.loc[ind2,'nod_i']
        df.loc[ind2,'nod_i']=df.loc[ind2,'nod_f']
        df.loc[ind2,'nod_f']=aux
        df2=df[df[dirn].apply(lambda x: not isinstance(x,str))].copy()
        df2['cords']=df2['cords'].apply(twist)
        aux=df2['nod_f'].to_list()
        df2['nod_f']=df2['nod_i']
        df2['nod_i']=aux
        df2['geometry']=df2['cords'].apply(cords_geo)
        df_f=pd.concat([df,df2])
        df_f[dirn]=['FT']*len(df_f)
    df_f['source']=df_f['nod_i']
    df_f['target']=df_f['nod_f']
    return df_f
 
def geo_to_cords(elem):
    '''
    This functions gets the coordinates of all points belonging to an elements

    Parameters
    ----------
    elem :shapely geometry object

    Returns
    -------
    cords : list of cords

    '''
    cords=[]
    if isinstance(elem,shapely.geometry.MultiLineString):
        for l in elem:
            cords.append(list(l.coords))
    elif isinstance(elem,shapely.geometry.LineString):
        cords.extend(elem.coords)
    elif isinstance(elem,shapely.geometry.LineString):
        cords.extend(elem.exterior.coords)
    else:
        cords.extend(elem.representative_point())
    
    return cords

def getXY(geo):
    '''
    Get significant point of a given geometry ( may be centroid or other thing)

    Parameters
    ----------
    geo : shapely geomtry

    Returns
    -------
    TYPE
        DESCRIPTION.
    TYPE
        DESCRIPTION.

    '''
    if isinstance(geo,shapely.geometry.LineString) or isinstance(geo,shapely.geometry.MultiLineString) :
        pt=geo.centroid
    else:
        pt=geo.representative_point()
    return (pt.x, pt.y)

def ckdnearest(gdA, gdB, bcol,opt=0):   
    '''
    This function matches the closest point of representative 
    point of gpdf B to the closest one of A for each point in
    putting the bcol of gdb of interest on each line of gdA

    Parameters
    ----------
    gdA : geopandas dataframe
    gdB : geopandas dataframe

    bcol : columns of b to put in gdA
    opt=0 just matches representativew points, option=1 is for matchine lines to lines
    Returns
    -------
    gdA : GDA with matched columns of gdb

    '''
   
    gdA=gdA.copy()
    gdB=gdB.copy()

    if opt==0: 
        nA = list(gdA['geometry'].apply(getXY))
        nB=list(gdB['geometry'].apply(getXY))
        
        btree = cKDTree(nB)
        dist, idx = btree.query(nA,k=1)
        gdA['distance']=dist.astype(float)
        gdA['rep_cords']=list(map(str,nA))

        for b in bcol:
            gdA[b]=gdB.loc[idx, b].values
    elif opt==1:
        gdA['cords']=gdA['geometry'].apply(geo_to_cords)
        gdB['cords']=gdB['geometry'].apply(geo_to_cords)
        #gdB['length(m)']=gdB['cords'].apply(distance)
       # gdA['length(m)']=gdA['cords'].apply(distance)
        
        nA = list(gdA['geometry'].apply(getXY))
        nB = list(gdB['geometry'].apply(getXY))
        btree = cKDTree(nB)
        dist, idx = btree.query(nA,k=10)
        ind_r=[]
        f=len(idx)
        pbar = tqdm(total = f)
        for i in range(f):
            #print_p(i,f,tst='percentage=')
            pbar.update(1)
            cors1=gdA.loc[i,'cords']
            cors2=gdB.loc[idx[i],'cords']
            m1=cors2.apply(get_metric,args=(cors1,))*dist[i,:]
            ind_r.append(m1.idxmin())
        for b in bcol:
            gdA[b]=gdB.loc[ind_r, b].values
                
    return gdA

def get_metric(cor1,cor2):
    '''
    measures the relationship beetween area and len of two sets of points to give a maetric of how relataed they are

    Parameters
    ----------
    cor1 : list of coordinates.
    cor2 : TYPE
        DESCRIPTION.

    Returns
    -------
    TYPE
        DESCRIPTION.

    '''
    pts=(cor1+cor2)
    hull = shapely.geometry.MultiPoint(pts).convex_hull
    return hull.area

def print_p(c,f,tst='percentage='):
    if (c/f*100)%1==0:
        print(tst+str(int(c/f*100)))
        
def correct_reduce(shape):
    '''
    This function corrects and prepair a given shape for reduction though combination and connectivity

    Parameters
    ----------
    shape : string: name of shape file to dead

    Returns
    -------
    df3 : dataframe with info of edge list

    '''
    #actual reduction
    gdf1= gp.read_file(shape)

    propertyy='SPEED'
    shape2=gdf1[gdf1[propertyy]>=35]
    # first shape correction and inversion of directions plus adding two ways links
    df2,cords=shape_corrector(shape2)
    #gdf11=gp.GeoDataFrame(df2.loc[:,['fid','ACC','Label','Shape_Leng', 'geometry','OneWay','SPEED','nod_i','nod_f','or_fid','FromZlev', 'ToZlev']])
    # gdf11['fid']=list(range(len(gdf11)))
    #gdf11.to_file('try1.gpkg',driver="GPKG")
    df2=df2.reset_index(drop=True)
    df2['Shape_Leng(m)']=df2['cords'].apply(distance)
    #reduction process and result 1 printing with all properties
    
    #selection of fields to export 
    field=['fid','ACC','Label','Shape_Leng(m)', 'geometry','OneWay','SPEED','cords', 'c_ini', 'c_fin','nod_i','nod_f','st_width','snow_pri','bike_lane','FromZlev', 'ToZlev','NYSStreetI']
    check_f=[i in df2.columns for i in field]
    G=nx.from_pandas_edgelist(df2.copy(),source='nod_i',target='nod_f',edge_attr=field)
    graphs2 = list(G.subgraph(c) for c in nx.connected_components(G))
    len_subg2=list(map(len,graphs2))
    in1=np.argmax(len_subg2)
    G2=graphs2[in1]
    
    df2=nx.to_pandas_edgelist(G2)
    
    # turn links that are drawn inverse to the real direction based on the field OneWay
    df3=correct_direction(df2.copy())

    #assigns the node names again after the direction correction
    df3,cords=shape_corrector(df3.copy())
    
    
    df3['source']=df3['nod_i']
    df3['target']=df3['nod_f']
    df3['or_fid']=df3['fid']
    df3['fid']=list(range(len(df3)))

    return df3,cords

def remove_heigths(dfr):
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
    ind11=6016
    df3=dfr.copy()
   
    ind2=df3['change_dir']==1
    aux=df3.loc[ind2,'FromZlev'].copy()
    df3.loc[ind2,'FromZlev']=df3.loc[ind2,'ToZlev']
    df3.loc[ind2,'ToZlev']=aux
    
    prop1='ToZlev'
    prop2='FromZlev'
    G=nx.from_pandas_edgelist(df3.copy(),source='source',target='target',edge_attr=True,create_using=nx.DiGraph)
    G2=G.copy()
    new_node=G.number_of_nodes()
    nodes=list(G.nodes)
    for i in nodes:
   
        neigs=list(G.neighbors(i))
        level={}
        for j in neigs:
            l=G[i][j][prop2]
            if  l in level.keys():
                level[l].append((j,'nod_f'))
            else:
                level[l]=[(j,'nod_f')]
        pred=list(G.predecessors(i))
        for j in pred:
            l=G[j][i][prop1]
            if  l in level.keys():
                level[l].append((j,'nod_i'))
            else:
                level[l]=[(j,'nod_i')]
                
        if len(level)>1:
         
            for lev in level:
                new_node+=1
                for j in level[lev]:
                    if j[1]=='nod_i':
                        edge=G[j[0]][i].copy()
                        edge['nod_f']=new_node
                        G.add_edge(j[0],new_node,**edge)
                        G.remove_edge(j[0],i)
                    else:
                        edge=G[i][j[0]].copy()
                        edge['nod_i']=new_node
                        G.add_edge(new_node,j[0],**edge)
                        G.remove_edge(i,j[0])
    df_f=nx.to_pandas_edgelist(G)
    df_f['source']=df_f['nod_i']
    df_f['target']=df_f['nod_f']
    
    return df_f

def add_ele_strong(df4,dirn='OneWay'):
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
    df5= correct_direction(df4,dirn,opt=1) 
    df_f=get_connectivity(df5) 
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
    gdf,cords=shape_corrector(gdf)
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
    shape : gp dataframe must have the field:['nod_i','nod_f','geometry','NUMBEROFLA','SPEED','Direction',
    'TRAVELTIME(S)','elevation_i(ft)','or_fid','snow_pri']

    Returns
    -------
    gdf : TYPE
        DESCRIPTION.

    '''
    if isinstance(shape,str):
        gdf=gp.read_file(shape)
    elif isinstance(shape,gp.GeoDataFrame) or isinstance(shape,pd.DataFrame):
        gdf=shape.copy()


    gdf['Direction']=1
    G=nx.from_pandas_edgelist(gdf,edge_attr=True)
    G2=nx.from_pandas_edgelist(gdf,edge_attr=True,create_using=nx.DiGraph)
    edges_u=G.edges
    edges_d=G2.edges
    
    for i in edges_u:
        edge=G[i[0]][i[1]]
        if i in edges_d and i[::-1] in edges_d:
            edge['Direction']=0
    df3=nx.to_pandas_edgelist(G)
    gd3=gp.GeoDataFrame(df3)
    
    gd3['source']=gd3['nod_i']
    gd3['target']=gd3['nod_f']
    gd3['length(m)']=gd3['geometry'].apply(distance)
    dicr={'Direction':'DIR','length(m)':'LENGTH(m)','SPEED':'SPEEDLIMIT'}
    
    inds = gd3['NUMBEROFLA']==0
    gd3.loc[inds, 'NUMBEROFLA']=2
    gd3['TRAVELTIME(S)']=gd3['length(m)']/(0.44704*gd3['SPEED'])
    gd3['AB_SPEED_L']=gd3['SPEED']
    inds=gd3['Direction']==0
    gd3['BA_SPEED_L']=0
    gd3.loc[inds, 'BA_SPEED_L']=gd3['AB_SPEED_L'][inds]
    gd3['AB_LANES']=gd3['NUMBEROFLA']
    gd3['BA_LANES']=0
    gd3.loc[inds, 'AB_LANES']=np.ceil(gd3['NUMBEROFLA'][inds]/2)
    gd3.loc[inds, 'BA_LANES']=gd3['NUMBEROFLA'][inds]-gd3['AB_LANES'][inds]
    inds2=(gd3['Direction']==0) & (gd3['BA_LANES']==0)
    
    gd3.loc[inds2,'BA_LANES']=1
    gd3=gd3.rename(columns=dicr)
    cols=['source','target','DIR','LENGTH(m)','SPEEDLIMIT','NUMBEROFLA','TRAVELTIME(S)','AB_SPEED_L','BA_SPEED_L','AB_LANES',\
          'BA_LANES','elevation_i(ft)','elevation_f(ft)','geometry','or_fid','snow_pri']
    gdf=gd3.loc[:,cols]
    gdf['LENGTH(m)']= gdf['geometry'].apply(distance)

    gdf['FN']=gdf['source']
    gdf['TN']=gdf['target']
    gdf['nLane']=gdf['AB_LANES']+gdf['BA_LANES']
    gd3['NUMBEROFLA']=gdf['nLane']
    gdf = gdf.loc[:,~gdf.columns.duplicated()]
    return gdf

def def_dir_ang(ang,tetas,opt=1):
    '''
    This function classifies a list of angles as being T,l,R,U

    Parameters
    ----------
    ang : TYPE
        DESCRIPTION.
    tetas : TYPE
        DESCRIPTION.
    opt : TYPE, optional
        DESCRIPTION. The default is 1.

    Returns
    -------
    dirs : TYPE
        DESCRIPTION.

    '''
    dirs=[]
    for i in ang:
        if i <tetas[0] or i>tetas[3]:
            dirs.append('Through')
        elif i<=tetas[1]:
            dirs.append('Left')
        elif i<=tetas[2]:
            if opt==1:
                dirs.append('Right')
            else:
                dirs.append('U')
        else:
            dirs.append('Right')
        
    return dirs

def list_tostr(lis):
    '''
    This function get a list of elements and then transform it to str being split with ;

    Parameters
    ----------
    lis :list

    Returns
    -------
    TYPE
        str

    '''
    return str(lis).replace('[','').replace(']','').replace(', ',';')

def transform_angles(angles):
    '''
    transform angle ( positive or negative) to angle beetween 0 to 36-

    Parameters
    ----------
    angles : TYPE
        DESCRIPTION.

    Returns
    -------
    angles : TYPE
        DESCRIPTION.

    '''
    angles=np.array(angles)
    a=angles<0
    angles[a]=360-np.mod(np.abs(angles[a]),360)
    angles=np.mod(angles,360)

    return angles

def remove_5legs(df,importance='nLane',offset=15,dis_t='wgs'):
    '''
    
    This function takes an undirected graph and removes 5 leg-intersections

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
    G=nx.from_pandas_edgelist(real,edge_attr=True,source='source',target='target')
    G2=G.copy()
    selected={}
    id_l=G.number_of_edges()
    selected={}
    nodes=list(G2.nodes)
    new_node=max(nodes)
    
    for i in nodes:
        #i=3930
     
        neig=list(G2.neighbors(i))
        selected={}
        if len(neig)>4:
            
            ang=[]
            inds_or=[]
            lanes=[]
            edges=[]
            for j in neig:
                edge=G2[i][j].copy()
                if edge['length(m)']<offset:
                    print('error with link='+str(i)+'-'+str(j))

                if edge['nod_i']==i:
                    ang.append(edge['angi'])
                    edges.append(edge)
                else:
                    ang.append( transform_angles(180+edge['angf']))
                    edge['cords']=edge['cords'][::-1]
                    edge['nod_f']=edge['nod_i']
                    edge['nod_i']=i
                    
                    edge['flipped']=1
                    edges.append(edge)
                
                inds_or.append(j) 
                lanes.append(edge[importance])
                
            inds=np.argsort(np.array(ang))
            neis=n_neigh(inds,1)
            lanes_ind=np.argsort(-np.array(lanes))#this is done to decide the 4 main roads
            main_angles=list(itemgetter(*lanes_ind[0:4])(ang))
            principal_roads=itemgetter(*lanes_ind[0:4])(inds_or)
            
            for j in range(4,len(lanes_ind)):
                
                
                id_l+=1
                ang_act=ang[lanes_ind[j]]
                dif_ang=[min_ang(an,ang_act) for an in main_angles] 
                ind_minang=np.argmin(dif_ang)
                ind_prin=lanes_ind[ind_minang]# principal road to join to
                
                ind_selected=lanes_ind[j]#actual index of road to be join to principal
                
                if ind_prin in selected:
              
                    point=selected[ind_prin][0]
                    new_node=selected[ind_prin][1]
                    
                    edge_mod=edges[ind_selected].copy()
                    node_mod=edge_mod['nod_f']
                    if edge_mod['length(m)']< offset*2:
              
                        d=0.5*edge_mod['length(m)']
                    else:
                        d=offset
                    
                    info_main2=divide_line(edge_mod['cords'],d,dis_t)
                    
                    cords_m=[point]+info_main2['line2']
                    if edge_mod['flipped']==0:
                        edge_mod['cords']=cords_m
                        edge_mod['nod_i']=new_node
                        edge_mod['nod_f']=node_mod
                    else:
                        edge_mod['cords']=cords_m[::-1]
                        edge_mod['nod_i']=node_mod
                        edge_mod['nod_f']=new_node
                    edge_mod['length(m)']=distance(edge_mod['cords'],0,dis_t)
                    edge_mod['geometry']=LineString(edge_mod['cords']) 
                    
                    G2.add_edge(new_node,node_mod,**edge_mod)
                    G2.remove_edge(i,node_mod)
        
                    edge=G2[i][new_node]
                    edge[importance]+=edge_mod[importance]
                else:
                    new_node=G2.number_of_nodes()+1
                    
                    edger=edges[ind_prin]
                    node_real=edger['nod_f']
                    edgem=edges[ind_selected]
                    node_mod=edgem['nod_f']
                    tst='combino'+str(i)+str(node_mod)+'a'+str(i)+str(node_real)
                    #print(tst)
                    #division of main road
                    if edger['length(m)']< offset*2:   
                        d=0.5*edger['length(m)']
            
                    else:
                        d=offset
                    info_main=divide_line(edger['cords'],d,dis_t)
                    
                    new_link=edger.copy()
                    
                    new_link[importance]=edger[importance]+edgem[importance]
                    new_link['virtual']+=1
                    if new_link['flipped']==0:
                        new_link['nod_i']=i
                        new_link['nod_f']=new_node
                        new_link['cords']=info_main['line1']
                        
      
                    else:
                        new_link['nod_i']=new_node
                        new_link['nod_f']=i
                        new_link['cords']=info_main['line1'][::-1]
                    new_link['length(m)']=distance(new_link['cords'],0,dis_t)
                    new_link['geometry']=LineString(new_link['cords'])
                    G2.add_edge(new_node,i,**new_link)

                    past_link=edger.copy()
                    
                    if past_link['flipped']==0:
                        past_link['cords']=info_main['line2']
                        past_link['nod_i']=new_node
                        past_link['nod_f']=node_real
                    else:
                        past_link['cords']=info_main['line2'][::-1]
                        past_link['nod_i']=node_real
                        past_link['nod_f']=new_node
                    past_link['length(m)']=distance(past_link['cords'],0,dis_t)
                    past_link['geometry']=LineString(past_link['cords'])
                    G2.add_edge(new_node,node_real,**past_link)
                    G2.remove_edge(i,node_real)
                    
                    edge_mod=edgem.copy()
                    if edge_mod['length(m)']< offset*2:
    
                        d=0.5*edge_mod['length(m)']
                        
                    else:
                        d=offset
                    
                    info_main2=divide_line(edge_mod['cords'],d,dis_t)
                    cords_m=[info_main['point']]+info_main2['line2']
                    if edge_mod['flipped']==0:
                        
                        edge_mod['cords']=cords_m
                        edge_mod['nod_i']=new_node
                        edge_mod['nod_f']=node_mod
                    else:
                        edge_mod['cords']=cords_m[::-1]
                        edge_mod['nod_i']=node_mod
                        edge_mod['nod_f']=new_node
                    edge_mod['length(m)']=distance(edge_mod['cords'],0,dis_t)
                    edge_mod['geometry']=LineString(edge_mod['cords'])    
                    G2.add_edge(new_node,node_mod,**edge_mod)
                    G2.remove_edge(i,node_mod)
                    
                    selected[ind_prin]=[info_main['point'],new_node]
          
    
    df2=nx.to_pandas_edgelist(G2) #,source='nod_i',target='nod_f')
    df2=gp.GeoDataFrame(df2)
    # df2.plot()
    # print(df2)
    df2['LENGTH(m)']= df2['geometry'].apply(distance)
    df2=df2.drop(columns=['length(m)'])
    df2['Id']=range(0,len(df2))
    vals=list(zip(*df2['geometry'].apply(get_angles).to_list()))
    df2['angi']=vals[0]
    df2['angf']=vals[1]
    print('Resulting network is connected?='+str(nx.is_connected(G2)))
    dd=[[len(list(G2.neighbors(n))),n] for n in G2.nodes]
    dfa=pd.DataFrame(dd,columns=['#neigh','node'])
    print('resulting max number of legs='+str(int(dfa['#neigh'].max())))
    inda=df2['virtual']==1
    df2.loc[inda, 'Direction']=0
    return [df2,G2,dfa]

def tests_f(offs=15,name1='test_5legsv2.csv',name2='test_5legsv2_cords.csv'):
    '''
    This function tests the 5legs remover on a example with a given distance

    Parameters
    ----------
    name1 : TYPE, optional
        DESCRIPTION. The default is 'test_5legsv2.csv'.
    name2 : TYPE, optional
        DESCRIPTION. The default is 'test_5legsv2_cords.csv'.

    Returns
    -------
    test : TYPE
        DESCRIPTION.

    '''
    test=pd.read_csv(name1)               
    cords=pd.read_csv(name2)
    cords['x']*=100
    cords['y']*=100
    test['geometry']=None
    test['length(m)']=0
    test['Id']=range(len(test))      
    test['nLane']=test['lanes']
    test['DIR']=0
    test['Direction']=0
    test['source']=test['FN']
    test['target']=test['TN']
    
    for i in test.index:
        cord1=tuple(cords.loc[test.loc[i,'FN']-1,['x','y']].to_list()) 
        cord2=tuple(cords.loc[test.loc[i,'TN']-1,['x','y']].to_list())  
        test.loc[i,'geometry']=LineString([cord1,cord2])
        test.loc[i,'length(m)']=np.linalg.norm(np.array(cord1)-np.array(cord2))
    gdf=gp.GeoDataFrame(test)
    gdf.plot()
    ans1=remove_5legs(test,importance='nLane',offset=offs,dis_t='proy')
    ans1[0].plot()
    return test

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
    fields=['Id','nLane','tLinkID','FN','TN','Lane1','linkID','freeflowsp','FREEFL01','length','geometry']
    df['def']=-1
    initial=df.copy()
    
    real=initial.loc[:,['or_fid','AB_LANES','def','source','target','def','or_fid','AB_SPEED_L','def','LENGTH(m)','geometry']]
    real.columns=fields
    inds = initial['DIR']==0
    real.loc[inds, 'tLinkID']=real['Id']
    
    double=df[df['DIR']==0].copy()
    aux=double['target'].copy()
    double['target']=double['source']
    double['source']=aux
    double['geometry']=double['geometry'].apply(reverse_geo)
    double_j=double.loc[:,['or_fid','BA_LANES','or_fid','source','target','def','or_fid','AB_SPEED_L','def','LENGTH(m)','geometry']]
    double_j.columns=fields
    
    real2=pd.concat([real,double_j])
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
    
    for i in range(len(real3['tLinkID'])-1):
        a1=real3.loc[i,'tLinkID']
        a2=real3.loc[i+1,'tLinkID']
        if a1==a2 and a1!=-1:
            real3.loc[i,'extra']=real3.loc[i+1,'Id']
            real3.loc[i+1,'extra']=real3.loc[i,'Id']
    real3['tLinkID']=real3['extra']        
    real3['linkID']=real3['Id']
    cols2=['FREEFL'+str(i) for i in range(1,25)]
    
    real3['Left']=0
    real3['Through']=0
    real3['Right']=0
    t='Through'
    r='Right'
    l='Left'
    real3['nod_i']=real3['FN']
    real3['nod_f']=real3['TN']

    # real3.drop(['TN','FN'], axis = 1, inplace = True)

    max_lanes=int(real3['nLane'].max())
    
    cols=['Lane'+str(k) for k in range(1,max_lanes+1)]
    real3=real3.drop(columns=['Lane1'])
    for c in cols:
        real3[c]=0
    G=nx.from_pandas_edgelist(real3,edge_attr=True,source='FN',target='TN',create_using=nx.DiGraph)
    tetas=[45,180,240,315]
    lanes_df=[]
    cols_l=['Id','Link','lanes_id','laneID','length','geometry']
    val=5.49*10**-7
    delta=0.01
    import random
    linkid='linkID'
    number_lanes='nLane'
    
    nodes=list(G.nodes)
    
    for i in nodes:
       
        neig_r=list(G.neighbors(i))
        pred=list(G.predecessors(i))
        for j in pred:
        
            edge=G[j][i]
            angs=[]
            angi=edge['angf']
    
            lanes=list(range(edge[linkid]*10+1,int(edge[linkid]*10+edge[number_lanes])+1))
            cols=['Lane'+str(k) for k in range(1,int(edge[number_lanes])+1)]
            for c in range(len(cols)):
                edge[cols[c]]=lanes[c]
                
            neig=Diff_l1l2(neig_r,[j])
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
                print('intersection with too many neighbors in i='+str(i))
                return pd.DataFrame([])
       
            
    dfff=nx.to_pandas_edgelist(G)
    dfff['FN'] = dfff['nod_i']
    dfff['TN'] = dfff['nod_f']
    dfff=dfff.loc[:,real3.columns]
    la=dfff.loc[:,[r,l,t]].sum(axis=1)
    print('elements with no neighbors='+str(len(la[la==0])))
    dfff['FN']=dfff['nod_i']
    dfff['TN']=dfff['nod_f']
    cols_r=[ 'FREEFL01','angi', 'angf', 'extra','nod_i', 'nod_f',]
    df_f=dfff.drop(columns=cols_r)
    for c in cols2:
        df_f[c]=dfff['freeflowsp']
    df_f['fid']=df_f[linkid]
    return df_f

def mathch_lanes(own_lanes,lanes):
    '''
    This function takes a set of lanes belong to a road and matches them to a series of lanes
    of all the other roads this road connects to. 

    Parameters
    ----------
    own_lanes : list of lane ID 
    lanes : Disctionary of lanes belonging to each road ID

    Returns
    -------
    unions : list of tuples with the pairs of joins beetween lane IDS

    '''
    initial_lanes=own_lanes[:]
    dic_neigh=copy.copy(lanes)
    lens=[len(i) for i in dic_neigh.values()]
    l_m=initial_lanes[0]
    r_m=initial_lanes[-1]
    if len(initial_lanes)==1:
        central=initial_lanes
    elif len(initial_lanes)==2:
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

def create_lane_shape(road,val=0.00005):
    '''
    This function adds lanes to a road shape and the  connectivity info of them
    This lane shape has the fields ['ID','Link','Left','Through','Right','laneID','length','geometry']
    the fields left through and right indicates if the lane this lane connect to is a left,right or
    through turn

    Parameters
    ----------
    road : road shape as geopandas dataframe
    val : distance, in this casde the value correponds to approximate 5 meters ( in wgs 84 coords)

    Returns
    -------
    df_f2 : lane shape geodataframe

    '''
    cols2=['ID','Link','Left','Through','Right','laneID','length','geometry']
    df_f=road.copy()
    linkid='linkID'
    t='Through'
    r='Right'
    l='Left'
    number_lanes='nLane'
    df_f['nod_i']=df_f['FN']
    df_f['nod_f']=df_f['TN']

    df_f=df_f.set_index(linkid)
    edgs=list(df_f.index)
    lane_count=1
    result=[]
    adv=-1
    multiss=[]

    pbar = tqdm(total = len(edgs))
    for i in edgs:
    
        adv+=1
        #print(str(round(adv/len(edgs)*100,2)))
        pbar.update(1)
        edge=df_f.loc[i,:]

        cols=['Lane'+str(k) for k in range(1,int(edge[number_lanes])+1)]
        own_lanes=edge[cols].to_list()
        lanes={}
        for m in [r,t,l]:
            neig=df_f.loc[i,m]
            if neig!=0:
                edge2=df_f.loc[neig,:]
                cols=['Lane'+str(k) for k in range(1,int(edge2[number_lanes])+1)]
                lanes[(neig,m)]=edge2[cols].to_list()
        unions=mathch_lanes(own_lanes,lanes)
        for k in own_lanes:
            if not k in unions:
                unions[k]=[]
        count=-1
        offset=np.arange(val,val*(len(unions)+1),val)
        for ll in unions:
            count+=1
            lane_count+=1
            dic_p={t:0,r:0,l:0}
            for c in unions[ll]:
                dic_p[c[1]]=c[0]
            geom_i=edge['geometry']
            geo2=LineString(paralel_off(geom_i,offset[count]))
            dis=distance(geo2)
            result.append([lane_count,i,dic_p[l],dic_p[t],dic_p[r],ll,dis,geo2])
            
    df_f2=pd.DataFrame(result,columns=cols2)
    return df_f2

def transform_shape(input_folder, output_folder = 'ouptput/', elev = None, cache_res=False):
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
    # Phase 1: join 2 sources of info
    # We skip this as it take times
    # shape1=input_folder + 'NY(STATEDOT)_streets_of_NYC.gpkg'
    # gdfNYS=gp.read_file(shape1)
    # shape2=input_folder +'NYC(DOT)_streets_of_NYC.gpkg'
    # gdfNYC=gp.read_file(shape2)
    
    # bcol=['st_width','snow_pri','bike_lane']
    # gdA=ckdnearest(gdfNYS, gdfNYC, bcol,1)
    # if cache_res:
    #     gdA2=graph_togpk(gdA, iutput_folder + 'NYC_joined.shp')
    name0 = input_folder +'NYC_joined.gpkg'

    # Phase 2: proccesing shape
    # in this stage some links are removed based on the velocity also just connected graph is stored
    name1= output_folder +'NYC_joined_corrected.gpkg'
    
    df2,cords=correct_reduce(name0)
    if cache_res:
        df2=graph_togpk(df2, name1)   
    print('phase 2 completed=add coordinates and nodes')
    
    
    # Phase 3: fix heights intersections
    df3=remove_heigths(df2)
    name2=output_folder+'NYC_joined_correctedbridges.gpkg'
    if cache_res:
        df3=graph_togpk(df3,name2)           
    print('phase 3 completed=bridges corrected')

    # Phase 4: reduction of links based on degree 2 and alone links
    d2=reduc_shp_direc(df3)
    d2=reduc_shp_direc(d2[0].copy())
    d2=reduc_shp_direc(d2[0].copy())
    df4=d2[0]
    name4=output_folder+'NYC_joined_correctedbridges_red.gpkg'
    if cache_res:
        df4=graph_togpk(df4,name4)           
    print('phase 4 completed=reduction of links')
    
    
    # Phase 5: fix the 4 legs intersections
    df4['NUMBEROFLA']=np.round( df4['st_width']/12)
    anslegs=remove_5legs(df4,importance='NUMBEROFLA',offset=10,dis_t='wgs')
    df5=anslegs[0]
    name5=output_folder+'NYC_joined_correctedbridges_red_4legs.gpkg'
    df5['OneWay']=df5['Direction'].replace({0:None,1:'FT'})
    if cache_res:
        df5=graph_togpk(df5,name5)  
    print('phase 5 completed=fixing legs>4')

    # Phase 6: add strong connectivity 
    df6=add_ele_strong(df5,'OneWay')
    if (elev is not None):
        df6,gdpoints=add_height_link(df6,elev)
    else:
        df6['elevation_i(ft)'] = 0
        df6['elevation_f(ft)'] = 0
    name6=output_folder+'NYC_joined_correctedbridges_red_4legs_strong.gpkg'
    nod_name6=output_folder+'nodes_elevated.gpkg'
    if cache_res:
        df6=graph_togpk(df6,name6) 
        if (elev is not None):
            nodes=graph_togpk(gdpoints,nod_name6) 
    print('phase 6 complete=adding links to gain strong connectivity and height')
    
    # Phase 7: puts in the format for the simulator road and lane detector
    df7=transform_directed_to_lane(df6)
    name7=output_folder+'NYC_joined_correctedbridges_red_4legs_strong_sim.gpkg'
    if cache_res:
        df7=graph_togpk(df7,name7) 
    print('phase 7 completed=putting final touches to version of simulator')
        
    # Phase 8 
    df8=get_road_shape(df7)
    name8=output_folder+'road_fileNYC.shp'
    df8=graph_togpk(df8,name8) 
    df8.drop(['geometry'], axis = 1).to_csv(output_folder+'/'+'road_fileNYC.csv', index = None)
    print('phase 8 completed=createing road shape')
    
    # Phase 9
    df9=create_lane_shape(df8,val=0.00005)
    name9=output_folder+'lane_fileNYC.shp'
    df9=graph_togpk(df9,name9) 
    df9.drop(['geometry'], axis = 1).to_csv(output_folder+'/'+'lane_fileNYC.csv', index = None)
    
    print('phase 9 completed=creating lane shape')
    
    # return [df2,df3,df4,df5,df6,df7,df8,df9]


