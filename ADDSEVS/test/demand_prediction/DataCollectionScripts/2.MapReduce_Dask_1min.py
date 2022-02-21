#!/usr/bin/env python
# coding: utf-8

# In[1]:


sc


# In[2]:


sqlContext = SQLContext(sc)


# In[3]:


import dask.dataframe as dd
from dask import datasets
import pandas as pd
import datetime
import warnings
import geopandas as gpd
warnings.filterwarnings('ignore')


# In[4]:


get_ipython().system('pwd')


# In[5]:


zone = gpd.read_file('../Data/NYC Taxi Zones.geojson')
zones = zone['location_id'].unique()


# In[83]:


len(zones)


# In[1]:



def processto1min(pid, record):
    hubZone = {'JFK': 132, 'LGA': 138, 'PENN': 186, 'EWR': 1}
    import csv
    reader = csv.reader(record,delimiter=',')
    for row in reader:
        if len(row) == 17:
            #yellow
            PU = row[7]
            if PU != '' and PU != 'PULocationID':
                if int(PU) == hubZone[hub]:
                    if row[1] != '':
                        date = row[1].split(' ')[0]
                        year = date.split('-')[0]
                        if year == '2018':
                            time = row[1]
                            # change all seconds to 00
                            minute = int(time.split(':')[1])
                            minute = int(minute/granularity)*granularity
                            time = time[:-5] + str(minute).zfill(2) + ':00'
                            passenger = int(row[3])
                            #DOLocation
                            if row[8] != '':
                                DO = str(int(row[8]))
                                if DO in zones:
                                    yield ((time,DO),1)

        elif len(row) == 19:
            #green
            PU = row[5]
            if PU != '' and PU != 'PULocationID':
                if int(PU) == hubZone[hub]:
                    if row[1] != '':
                        date = row[1].split(' ')[0]
                        year = date.split('-')[0]
                        if year == '2018':
                            time = row[1]
                            minute = int(time.split(':')[1])
                            minute = int(minute/granularity)*granularity
                            time = time[:-5] + str(minute).zfill(2) + ':00'
                            passenger = int(row[7])
                            if row[6] != '':
                                DO = str(int(row[6]))
                                if DO in zones:
                                    yield ((time,DO),1)

        elif len(row) == 7:
            #fhv
            PU = row[2]
            if PU != '' and PU != 'PUlocationID' :
                if int(PU) == hubZone[hub]:
                    if row[1] != '':
                        date = row[0].split(' ')[0]
                        year = date.split('-')[0]
                        if year == '2018':
                            time = row[0]
                            minute = int(time.split(':')[1])
                            minute = int(minute/granularity)*granularity
                            time = time[:-5] + str(minute).zfill(2) + ':00'
                            if row[3] != '':
                                DO = str(int(row[3]))
                                passenger = 1
                                if DO in zones:
                                    yield ((time,DO),1)


# In[149]:


hub = 'PENN'
granularity = 5
hubZone = {'JFK': 132, 'LGA': 138, 'PENN': 186, 'EWR': 1}
processedFileDir = "../processedData/"
processedFile = processedFileDir+hub+"raw"+str(granularity)+'min'
rdd = sc.textFile(dataDir+'*.csv')


# In[150]:


get_ipython().run_cell_magic('time', '', 'counts = rdd.mapPartitionsWithIndex(processto1min).reduceByKey(lambda x,y:x+y).\\\n            map(lambda x: (x[0][0],x[0][1],x[1])).cache()\ndf = sqlContext.createDataFrame(counts,[\'time\',\'DOLocation\',\'count\'])\ndf.repartition(1).write.mode("overwrite").\\\n    format("com.databricks.spark.csv").option("header", "true").save(processedFile)\nprint(hub,\' done\')')


# In[151]:


processedFile


# In[152]:


os.listdir(processedFile)


# In[153]:



df = pd.read_csv(processedFile+'/'+[file for file in os.listdir(processedFile) if '.csv' in file and '.crc' not in file][0])
df.head()


# In[154]:


df['time'] = pd.to_datetime(df['time'])
df['Date'] = df['time'].dt.date
df['Hour'] = df['time'].dt.hour
df['Min'] = df['time'].dt.minute
df['vehicle_count'] = df['count']
df['DOLocationID'] = df['DOLocation']
del df['time']
del df['count']
del df['DOLocation']
df.to_csv('../processedData/'+hub+'VehicleBy'+str(granularity)+'Min.csv',index=False)


# In[93]:


import numpy as np
table = pd.pivot_table(df, values='vehicle_count', index=['Date','Hour','Min'],
                    columns=['DOLocationID'], aggfunc=np.sum, fill_value=0)
table


# In[54]:


'../processedData/'+hub+'VehicleBy'+str(granularity)+'Min.csv'


# In[ ]:





# In[ ]:





# In[ ]:


# add lag in mapreduce


# In[55]:


# sparse matrix
def lag(pid, record):
    hubZone = {'JFK': 132, 'LGA': 138, 'PENN': 186, 'EWR': 1}
    import csv
    reader = csv.reader(record,delimiter=',')
    for row in reader:
        time = row[0]
        DO = row[1]
        passenger = row[2]
        if time != 'time':
            time = (datetime.datetime.strptime(time, '%Y-%m-%d %H:%M:%S') + datetime.timedelta(minutes=lagMin*granularity))
            year = time.strftime("%Y")
            if year == '2018':
                time = time.strftime('%Y-%m-%d %H:%M')
                DO = DO +'lag'+str(lagMin)
                yield((time,DO),passenger)


# In[56]:


os.listdir(processedFile)


# In[34]:


# sparse matrix
# %%time
processedFilecsv = processedFile+'/'+os.listdir(processedFile)[1]
rdd = sc.textFile(processedFilecsv)
for lagMin in range(1,13):
    counts = rdd.mapPartitionsWithIndex(lag).reduceByKey(lambda x,y:x+y).                map(lambda x: (x[0][0],x[0][1],x[1])).cache()
    df = sqlContext.createDataFrame(counts,['time','DOLocation','count'])
    df.repartition(1).write.mode("append").format("com.databricks.spark.csv").option("header", "true").save(processedFile)
print(hub,' done')


# In[35]:


# sparse matrix with lag

df = dd.read_csv(processedFile+'/*.csv')
df.compute().to_csv('../processedData/'+hub+'VehicleBy'+str(granularity)+'Min.csv',index=False)


# In[37]:


processedFile


# In[38]:


df.head()


# In[33]:


df = dd.read_csv(processedFileDir+hub+'rawRenmaedFile/part-00000-3477f39e-47e2-402f-859e-fd8a4e407735-c000.csv')


# In[27]:


# 5min
df.time = dd.to_datetime(df.time,unit='ns')
df['5min'] = df.time.dt.minute
df['5min'] = df['5min'].apply(lambda x: int(x/5)*5)
df['time'] = df['time'].astype('str')
df['time'] = df.apply(lambda x:x['time'][:14]+str(x['5min'])+x['time'][:16],axis=1)
df.compute().to_csv(processedFileDir+hub+'VehicleBy5Min.csv',index=False)


# In[13]:


# 15min
df.time = dd.to_datetime(df.time,unit='ns')
df['15min'] = df.time.dt.minute
df['15min'] = df['15min'].apply(lambda x: int(x/15)*15)
df['time'] = df['time'].astype('str')
df['time'] = df.apply(lambda x:x['time'][:14]+str(x['15min']).zfill(2),axis=1)
df.compute().to_csv(processedFileDir+hub+'VehicleBy15Min.csv',index=False)


# In[12]:


df.compute().to_csv(processedFileDir+hub+'VehicleBy1Min.csv',index=False)


# In[10]:


df = dd.read_csv(processedFileDir+hub+'VehicleBy15Min.csv',usecols=['time','DOLocation','count'])
df.head()


# In[14]:


# lag value
for delta in range(1, 13):
    timedelta = 15*delta
    dfDelta = df.copy()
    dfDelta['count-'+str(delta)] = dfDelta['count']
    del dfDelta['count']
    dfDelta['time'] = dfDelta['time'].map(lambda x: x.split(":")[0]+':'+str(int(x.split(":")[1])+timedelta),
                                         meta=('time', 'str'))
    df = df.merge(dfDelta, on=['time','DOLocation'], how='outer')


# In[ ]:


df.compute().to_csv(processedFileDir+hub+'VehicleBy15MinLag.csv')


# In[12]:


# sparse
exclude_columns = ['time','5min','15min']
for (columnName, columnData) in df.iteritems():
    if columnName in exclude_columns:
        continue
    df[columnName] = pd.arrays.SparseArray(columnData.values, dtype='uint8')


# In[13]:


df


# In[2]:


df_edge = pd.DataFrame()
c_size = 5000
for df_chunk in pd.read_csv(processedFileDir+hub+'VehicleBy15Min.csv',chunksize=c_size):
    df_chunk = pd.pivot_table(df_chunk, index='time',columns='DOLocation',values='count',fill_value=0)
    df_edge = pd.concat([df_edge,df_chunk])


# In[7]:


chunks = pd.read_csv(processedFileDir+hub+'VehicleBy15Min.csv',chunksize=c_size)


# In[11]:


for chunk in chunks:
#     print(chunk)
    print(pd.pivot_table(chunk, index='time',columns='DOLocation',values='count',fill_value=0))
    break


# In[16]:


get_ipython().run_cell_magic('time', '', "pdf_edge = pd.pivot_table(pdf, index='time',columns='DOLocation',values='count',fill_value=0)")


# In[ ]:




