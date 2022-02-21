#!/usr/bin/env python
# coding: utf-8

# ### This script downloads the raw taxi and fhv ridership data from amazon storage.

# ### Parameters

# In[1]:


get_ipython().system('mkdir rawData')
dataDir = 'rawData'
Years = [2018]
Months = range(1,13)
VehicleTypes = ['fhv', 'yellow', 'green']


# In[2]:


import pandas as pd
import datetime
import urllib
import os
import glob
import warnings
import time
warnings.filterwarnings('ignore')


# In[3]:


def getUrl(cabtype,year,month):
    baseUrl = 'https://s3.amazonaws.com/nyc-tlc/trip+data/'
    
    if len(str(month)) == 1:
        fileName = '%s_tripdata_%s-0%s.csv'%(cabtype,year,month)
    else:
        fileName = '%s_tripdata_%s-%s.csv'%(cabtype,year,month)
        
    return baseUrl + fileName, fileName        


# In[ ]:


for year in Years:
    for month in Months:
        for cabtype in VehicleTypes:
            url, fileName = getUrl(cabtype,year,month)
            
            print("Downloading..: "+str(fileName))
            
            if fileName in os.listdir(dataDir):
                print("file exists...")
                continue
            
            filePath = os.path.join(dataDir, fileName)
            try:
                urllib.request.urlretrieve(url, filePath)
            except:
                # if fails remove the incomplete file
                os.remove(filePath)
                try:
                    # start again after a delay of 2 min
                    time.sleep(60)
                    urllib.request.urlretrieve(url, filePath)
                except:
                    print("Download this file later !!!!!!!!!!!!!")
                    pass

            print()


# In[ ]:




