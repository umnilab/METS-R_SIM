#!/usr/bin/env python
# coding: utf-8

# ### This notebook does the following:
# - processes raw ridership data
# - performs feature selection and cleaning
# - performs hourly aggregation
# - saves results in one file

# In[19]:


import pandas as pd
import os
from datetime import date, timedelta
import itertools


# In[20]:


# raw data directory
dataDir = '/rawData/'


# In[21]:


os.listdir('rawData/')


# ### identify zones of interest

# In[22]:


zones = pd.read_csv('https://data.cityofnewyork.us/api/views/755u-8jsi/rows.csv?accessType=DOWNLOAD')


# In[15]:


zone_dict = {'JFK':  132,
'LGA' : 138,
'PENN' : 186}
zone_dict


# ### Run following script for each hub

# In[16]:


# 'PENN', 'JFK', 'LGA'
hub = 'PENN'
zone = zone_dict[hub]
zone


# In[17]:


get_ipython().system('mkdir processedData')
processedFileDir = "../processedData/"
processedFile = processedFileDir+hub+"VehicleByHour.csv"


# In[18]:


validDestZones = list(set([z for z in zones.LocationID if z != zone]))
len(validDestZones)


# In[26]:


files = os.listdir('rawData/')
files


# In[ ]:


for file in files:
    if '.csv' in file:
        print("Processing "+str(file).split('/')[-1])

        vehicleType = str(file).split('/')[-1].split('_')[0]
        df = pd.read_csv('rawData/'+file)
        print("DataFrame Shape: "+str(df.shape))

        # rename columns for consistency
        # set passenger count to 1 for fhv
        if vehicleType == 'fhv':
            df.rename(columns={'Pickup_DateTime': 'tpep_pickup_datetime',                                'PUlocationID':'PULocationID', 'DOlocationID':'DOLocationID' },inplace=True)
            df['passenger_count'] = 1

        if vehicleType == 'green':
            df.rename(columns={'lpep_pickup_datetime': 'tpep_pickup_datetime'},inplace=True)


        # treat for na values
        df = df.dropna(subset=['tpep_pickup_datetime','PULocationID', 'DOLocationID'])
        df.fillna(value={'passenger_count':1}, inplace = True)

        # correct data types
        df['PULocationID'] = df['PULocationID'].astype('int')
        df['DOLocationID'] = df['DOLocationID'].astype('int')

        # filter to get outgoing traffic from selected hub
        df = df[(df['PULocationID'] == zone) & (df['DOLocationID'].apply(lambda x: x in validDestZones))]
        print("JFK out DataFrame Shape: "+str(df.shape))

        # treat datetime
        df['tpep_pickup_datetime'] = pd.to_datetime(df['tpep_pickup_datetime'])
        df['Date'] = df['tpep_pickup_datetime'].dt.date
        df['Hour'] = df['tpep_pickup_datetime'].dt.hour


        # select rquired columns
        df = df[['Date', 'Hour', 'DOLocationID','passenger_count']]

        df_count = df.groupby(['Date', 'Hour', 'DOLocationID']).count().reset_index()
        df_count.rename(columns={'passenger_count': 'vehicle_count'},inplace=True)

        
        aggregatedDf = df_count

        print("Aggregated DataFrame Shape: "+str(aggregatedDf.shape))
        print(aggregatedDf.head(3))
        # save file
        if os.path.exists(processedFile):
            print('append to results...')
            aggregatedDf.to_csv(processedFile,index=False, header=False, mode='a+')      
        else:
            print('create results file...')
            aggregatedDf.to_csv(processedFile,index=False)
        print('file saved..')
        print("------------------------------------------------")


# ### Further processing

# In[21]:


def getcCompleteGridDf(minDate,maxDate, locations):
    minDate = [int(x) for x in minDate.split('-')]
    maxDate = [int(x) for x in maxDate.split('-')]
    sdate = date(minDate[0], minDate[1], minDate[2])   
    edate = date(maxDate[0], maxDate[1], maxDate[2])    

    delta = edate - sdate       
    days = []
    for i in range(delta.days + 1):
        days.append(sdate + timedelta(days=i))
    hours = list(range(24))
    print(len(days))
    print(len(hours))
    
    combList = list(itertools.product(*[days,hours,locations]))
    dfList = [{'Date':d, 'Hour':h, 'DOLocationID':l} for d,h,l in combList]
 
    dateHourDf = pd.DataFrame(dfList)
    dateHourDf['Date'] = pd.to_datetime(dateHourDf['Date']).dt.date
    return dateHourDf


# In[22]:


processedDf = pd.read_csv(processedFile)
processedDf.head(2)


# In[23]:


processedDf.shape


# In[24]:


# ensuring proper grouping since files were grouped by independently
processedDf = processedDf.groupby(['Date', 'Hour', 'DOLocationID']).sum().reset_index()
processedDf.shape


# In[25]:


# sanity checks
validYears = [2018]
processedDf = processedDf[processedDf.Date.apply(lambda x: int(x.split('-')[0]) in validYears)]

validMonths = list(range(1,13))
processedDf = processedDf[processedDf.Date.apply(lambda x: int(x.split('-')[1]) in validMonths)]

processedDf.shape    


# In[26]:


minDate, maxDate = (processedDf.Date.min(), processedDf.Date.max()) 
#v_types = list(set(processedDf.vehicle_type))
locations = list(set(processedDf.DOLocationID))

#print(len(v_types))
print(len(locations))

dateHourDf = getcCompleteGridDf(minDate,maxDate,locations)
dateHourDf.shape


# In[27]:


dateHourDf['Date'] = pd.to_datetime(dateHourDf['Date'])
processedDf['Date'] = pd.to_datetime(processedDf['Date'])


# In[28]:


mergedDf = pd.merge(dateHourDf,processedDf, on=['Date', 'Hour', 'DOLocationID'], how='left')
mergedDf.fillna(0, inplace=True)
mergedDf['Date'] = mergedDf['Date'].dt.date
print(mergedDf.shape)
mergedDf.head(3)


# In[62]:


mergedDf['Date'] = mergedDf['Date'].astype('str')
mergedDf['Hour'] = mergedDf['Hour'].astype('str')
mergedDf['DOLocationID'] = mergedDf['DOLocationID'].astype('str')
mergedDf = mergedDf.sort_values(by=['Date','Hour','DOLocationID'])
mergedDf.to_csv(processedFile,index=False)

