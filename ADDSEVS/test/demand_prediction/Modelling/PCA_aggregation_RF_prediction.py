#!/usr/bin/env python
# coding: utf-8

# In[3]:


import pandas as pd
import os
from sklearn.decomposition import PCA
import numpy as np
import matplotlib.pyplot as plt

from sklearn.preprocessing import StandardScaler
from sklearn.metrics import r2_score
from sklearn.ensemble import RandomForestRegressor

from sklearn.model_selection import train_test_split
from sklearn.model_selection import RandomizedSearchCV
from sklearn.model_selection import cross_val_score
from sklearn.multioutput import MultiOutputRegressor
import warnings
warnings.filterwarnings('ignore')


# ### select hub

# In[ ]:


# 'JFK','LGA','PENN'
hub = 'JFK'


# ### Helper Functions

# In[2]:


def loadData(file):
    data = pd.read_csv(file)
    print('Raw shape: ',data.shape)
    data['Date'] = pd.to_datetime(data.Date)
    print('Days: ',len(set(data.Date)))
    return data


# In[3]:


def getTimeSeries(df):
    table = pd.pivot_table(df, values='vehicle_count', index=['Date','Hour'],
                    columns=['DOLocationID'], aggfunc=np.sum, fill_value=0)
    return table


# In[4]:


def zscoreNormalizeSpatial(matrix):
    m = matrix.copy()
    for i in range(m.shape[0]):
        m[i, :] = (m[i, :] - m[i, :].mean()) / (m[i, :].std()+1e-10)
        
    return m


# In[5]:


def standardize(matrix):
    m = matrix.copy()
    scaler = StandardScaler()
    scaler.fit(m)
    t = scaler.transform(m)
    return scaler, t
def inverse_standardize(matrix, scaler):
    t = matrix.copy()
    return scaler.inverse_transform(t)
def getPCAFeatures(matrix, n=10):
    pca = PCA(n_components=n)
    pca.fit(matrix)
    reducedMatrixPCA = pca.transform(matrix)
    reducedMatrixPCA.shape

    reducedDict = {str(i+1):reducedMatrixPCA[:,i] for i in range(reducedMatrixPCA.shape[1])}
    reducedDf = pd.DataFrame(reducedDict)
    #reducedDf.index = index
    return pca,reducedDf


# In[6]:


def inverse_standardize(matrix, scaler):
    t = matrix.copy()
    return scaler.inverse_transform(t)


# In[7]:


def getPCAFeatures(matrix, n=10):
    pca = PCA(n_components=n)
    pca.fit(matrix)
    reducedMatrixPCA = pca.transform(matrix)
    reducedMatrixPCA.shape

    reducedDict = {str(i+1):reducedMatrixPCA[:,i] for i in range(reducedMatrixPCA.shape[1])}
    reducedDf = pd.DataFrame(reducedDict)
    #reducedDf.index = index
    return pca,reducedDf


# In[8]:


def PCA_test(matrix, pca):

    reducedMatrixPCA = pca.transform(matrix)

    reducedDict = {str(i+1):reducedMatrixPCA[:,i] for i in range(reducedMatrixPCA.shape[1])}
    reducedDf = pd.DataFrame(reducedDict)
    #reducedDf.index = index
    return reducedDf


# In[9]:


def inverse_pca(matrix,pca):
    m = matrix.copy()
    return pca.inverse_transform(m)


# In[10]:


def addLag(dataset, maxlag, lagColumns):
    dataset_list = [dataset]

    for l in range(1, maxlag+1):
        df = dataset.shift(l)
        df = df[lagColumns]
        df.columns = [c+'_lag_'+str(l) for c in df.columns]
        dataset_list.append(df)

    dataset = pd.concat(dataset_list, axis=1).dropna()
    return dataset


# In[11]:


def get_rmse(matrix1, matrix2):
    sumSquareError = np.mean(np.power(matrix1 - matrix2,2))
    rmse = np.power(sumSquareError,0.5)
    return rmse


# In[12]:


def pca_performance(trainmatrix,testmatrix, components):
    rmseList = []
    r2List = []
    for n in components:
        scaler, s_train_matrix = standardize(trainmatrix)
        s_test_matrix = scaler.transform(testmatrix)

        pca,pcaTrain = getPCAFeatures(s_train_matrix,n=n)
        pcaTest = PCA_test(s_test_matrix, pca)
        
        network_prediction = inverse_pca(pcaTest,pca)
        network_prediction = inverse_standardize(network_prediction, scaler)

        r2Score = r2_score(testmatrix, network_prediction, multioutput='variance_weighted')
                
        r2List.append(r2Score)
    
    return r2List


# In[13]:


def nonlinearperformance(trainmatrix,testmatrix,components, maxlag=12):
    r2List = []
    for n in components:
        print(n)
        scaler, s_train_matrix = standardize(trainmatrix)
        s_test_matrix = scaler.transform(testmatrix)

        pca,pcaTrain = getPCAFeatures(s_train_matrix,n=n)
        pcaTest = PCA_test(s_test_matrix, pca)

#         maxlag = 12
        DateColumns = ['Date', 'Hour']
        lagColumns = [c for c in pcaTrain.columns if c not in DateColumns]

        dataset_train = addLag(pcaTrain, maxlag)

        dataset_test = addLag(pcaTest, maxlag)

        X_train = dataset_train.drop(lagColumns , axis = 1)
        X_test = dataset_test.drop(lagColumns , axis = 1)
        y_train = dataset_train[lagColumns]
        y_test = dataset_test[lagColumns]
#         print(X_train.shape)
#         print(X_test.shape)
#         print(y_train.shape)
#         print(y_test.shape)

        rf2 = RandomForestRegressor(random_state = 0, n_estimators=200, 
                                   min_samples_split=10,
                                   min_samples_leaf= 3, 
                                   max_features= 'sqrt',
                                   max_depth= 30, 
                                   bootstrap= True)

        rf2.fit(X_train,y_train)

        pca_prediction = rf2.predict(X_test)

        network_prediction = inverse_pca(pca_prediction,pca)

        network_prediction = inverse_standardize(network_prediction, scaler)

        r2Score = r2_score(testmatrix[maxlag:], network_prediction,                            multioutput='variance_weighted')
        
        r2List.append(r2Score)
    return r2List


# #### Preparing Data

# In[16]:


tune_hyp_params = False
pca_comps = 6


# In[17]:


dataDir = '../PredictionDataCollectionScripts/processedData/'
file = dataDir + hub + 'VehicleByHour.csv'


# In[18]:


# file = '/home/urwa/Documents/Projects/NYU Remote/project/data/JfkVehiceByHour.csv'


# In[19]:


data = loadData(file)


# In[20]:


data = getTimeSeries(data)


# In[21]:


data.shape


# In[22]:


data


# In[23]:


matrix = data.values.astype(np.float64)


# In[24]:


scaler, s_matrix = standardize(matrix)


# In[25]:


pca,pcaData = getPCAFeatures(s_matrix,n=pca_comps)


# In[26]:


pcaData.index = data.index
pcaData = pcaData.reset_index()


# In[27]:


externalDataDir = "../PredictionExternalData/"
extFile = externalDataDir + hub.upper() + ".csv"


# In[28]:


extDf = pd.read_csv(extFile)


# In[29]:


extDf['date'] = pd.to_datetime(extDf['date'], yearfirst=True)


# In[30]:


extDf['Hour'] = extDf['date'].dt.hour
extDf['Dow'] = extDf['date'].dt.dayofweek
extDf['Date'] = extDf['date'].dt.date


# In[32]:


selected_columns = ['Date', 'Hour', 'Dow', 'arrival','maxtemp', 'mintemp', 'avgtemp', 'departure', 'hdd',
       'cdd', 'participation', 'newsnow', 'snowdepth', 'ifSnow']


# In[33]:


extDf = extDf[selected_columns]


# In[35]:


pcaData['Date'] = pd.to_datetime(pcaData['Date'])
extDf['Date'] = pd.to_datetime(extDf['Date'])


# In[36]:


pcaData = pd.merge(pcaData,extDf, on=['Date', 'Hour'], how='inner')
print(pcaData.shape)


# In[37]:


pcaData.columns


# In[38]:


lagColumns = ['1', '2', '3', '4', '5', '6', 'arrival']
# lagColumns = ['1', '2', '3', 'arrival']

DateColumns = ['Date']

targetColumns = ['1', '2', '3', '4', '5', '6']


# In[39]:


maxlag = 12

pcaData_lag = addLag(pcaData, maxlag, lagColumns)

pcaData_lag.shape


# In[42]:


CommR2List = []
EdgeR2List = []
residualDf_list = []
rawList = []
networkPrediction = pd.DataFrame()

for m in range(1,13):
    print()

    print("month: ",m)
    month_index  = pd.to_datetime(pcaData_lag.Date).dt.month == m

    dataset_train = pcaData_lag[~month_index]
    dataset_test = pcaData_lag[month_index]
    print("Train Size: ",dataset_train.shape)
    print("Test Size: ",dataset_test.shape)


    X_train = dataset_train.drop(targetColumns+DateColumns , axis = 1)
    X_test = dataset_test.drop(targetColumns+DateColumns , axis = 1)
    y_train = dataset_train[targetColumns]
    y_test = dataset_test[targetColumns]



    rf2 = RandomForestRegressor(random_state = 2019, n_estimators=150, 
                               min_samples_split=3,
                               min_samples_leaf= 2, 
                               max_features= 'sqrt',
                               max_depth= None, 
                               bootstrap= False)

    rf2.fit(X_train,y_train)

    print("Train R2: ",rf2.score(X_train,y_train))
    test_r2 = rf2.score(X_test,y_test)
    print("Test R2: ",test_r2)


    pca_prediction = rf2.predict(X_test)

    residual = y_test - pca_prediction
    residual_df = dataset_test[['Date','Hour']]
    residual_df = pd.concat([residual_df,pd.DataFrame(residual)], axis =1)

    network_prediction = inverse_pca(pca_prediction,pca)

    network_prediction = inverse_standardize(network_prediction, scaler)
    network_prediction_df = pd.DataFrame(network_prediction)
    network_prediction_df.columns = data.columns
    networkPrediction = pd.concat([networkPrediction,network_prediction_df])
    edgeMonthIndex = [False] * maxlag + list(month_index)
    edge_r2 = r2_score(data[edgeMonthIndex], network_prediction, multioutput='variance_weighted')
    print("Edge R2: ",edge_r2)


    CommR2List.append(test_r2)
    EdgeR2List.append(edge_r2)
    residualDf_list.append(residual_df)
#     rawList.append()


# In[43]:


if os.path.exists('prediction'):
    os.makedirs('prediction')
networkPrediction['Date'] = data.reset_index().iloc[12:]['Date'].values
networkPrediction.to_csv('/prediction/%sPCA%s.csv'%(hub,pca_comps),index=False)


# In[45]:


print('aggregated R2' ,np.mean(CommR2List))
print('taxi zone R2' ,np.mean(EdgeR2List))

