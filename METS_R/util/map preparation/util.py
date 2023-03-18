# -*- coding: utf-8 -*-
"""
@author: Juan Esteban Suarez Lopez, Zengxiang Lei

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
    input : list of cords
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
    off : number describing the amount to be displaced if number is positive the displacement is to
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
        if np.linalg.norm(dire)>0:
            dire=dire/np.linalg.norm(dire) # direction
            dire_n=np.array([dire[1],-dire[0]])
            seg_norm=seg+off*dire_n
            new_segs.append(seg_norm)
        else:
            print("the length of link segement is 0")
            print(seg)
            print(cords)
    s=len(new_segs)
    new_points=[new_segs[0][0]]
    for i in range(s-1):
        # s1=new_segs[i]
        # s2=new_segs[i+1]
        # p1=s1[0]
        # dir1=s1[1]-s1[0]
        # p2=s2[0]
        # dir2=s2[1]-s2[0]
        # if np.linalg.norm(dir1)>0 and np.linalg.norm(dir2)>0:
        #     p=inter(dir1,p1,dir2,p2)
        new_points.append(list(new_segs[i+1][0]))
    new_points.append(new_segs[-1][-1])
    new_points=np.array(new_points)

    if(distance(new_points)>20000):
        print(p)
        print(new_points)
        print(new_segs)

    return new_points
      
def inter(dir1,p1,dir2,p2):
    '''
    This function finds the intersection of two lines defined by it's direction and a point

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

    if None in cords:
        print("None in cords!")
        cords = list(filter(None,cords))
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
        nB = list(gdB['geometry'].apply(getXY))
        
        btree = cKDTree(nB)
        dist, idx = btree.query(nA,k=1)
        gdA['distance']=dist.astype(float)
        gdA['rep_cords']=list(map(str,nA))

        for b in bcol:
            gdA[b] = gdB.loc[idx, b].values
    elif opt==1:
        gdA['cords'] = gdA['geometry'].apply(geo_to_cords)
        gdB['cords'] = gdB['geometry'].apply(geo_to_cords)
        #gdB['length(m)']=gdB['cords'].apply(distance)
        #gdA['length(m)']=gdA['cords'].apply(distance)
        
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
    try:
        hull = shapely.geometry.MultiPoint(pts).convex_hull
        return hull.area
    except:
        return np.inf


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

def print_p(c,f,tst='percentage='):
    if (c/f*100)%1==0:
        print(tst+str(int(c/f*100)))

def get_coords(df2=[]):
    if len(df2)>0:
        df2['geometry']=df2['geometry'].apply(get_linestring)
        df2['cords']=df2['geometry'].apply(get_coordinates)
    return df2