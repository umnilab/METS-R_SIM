#!/usr/bin/env python

# Copyright (c) UMNILAB.
# Generate breif summary of the simulation results
#
# This work is licensed under the terms of the MIT license.
# For a copy, see <https://opensource.org/licenses/MIT>.

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import tkinter
from tkinter import filedialog
import os

def plot_service_performance(df_ev):
    # Spatio-temporal patterns
    df_ev['timeIndex'] = df_ev['departureTime']//6000
    df = df_ev[df_ev['tripType']==1].groupby(['timeIndex']).count()
    df2 = df_ev[(df_ev['tripType']==2)|((df_ev['tripType']==7))].groupby(['timeIndex']).count()
    df3 = df_ev[df_ev['tripType']==4].groupby(['timeIndex']).count()
    df4 = df_ev.groupby(['timeIndex'])['cost'].sum()

    fig, ax = plt.subplots(figsize = (6,4))

    ax.bar(df.index[6:54], df['tick'].values[6:54], color = 'C1', label = 'Occupied')
    ax.bar(df2.index[6:54], df2['tick'].values[6:54], bottom = df['tick'].values[6:54], color = 'C2', label = 'Repopsitioning')
    ax.bar(df3.index[6:54], df3['tick'].values[6:54], bottom = df['tick'].values[6:54]+df2['tick'].values[6:54], color = 'royalblue', label = 'Charging')

    ax2 = ax.twinx()
    l1, = ax2.plot(df4.index[6:54], df4.values[6:54], '--', color = 'red', lw = 1, label = 'Energy consumption')

    handles, labels = ax.get_legend_handles_labels()

    ax.set_xlim([6, 54])

    ax.set_ylabel('Number of trips')
    ax2.set_ylabel('Energy consumption (kWh)')

    ax.set_xlabel('Hour of day')

    ax.set_xlim(6-0.5,54-0.5)

    ax.set_ylim([0,5000])
    ax2.set_ylim([0,12000])

    ax.legend(handles + [l1], labels+ ['Energy consumption'], ncol=2, loc=2)

    ax.set_xticks(np.arange(6-0.5,54-0.5+1,4) , np.arange(0,25,2))

    plt.tight_layout()

    plt.pause(5)

    plt.show(block=False)

def plot_trajectory(df_traj):
    df_traj = df_traj.sort_values(['vehicleID', 'tick'], ascending = True)

    # Energy consumption versus speed and acceleration
    fig = plt.figure(figsize=(6,4))
    ax = plt.axes(projection="3d")

    df_sample = df_traj.sample(50000)

    ax.scatter3D(df_sample['speed'], df_sample['acc'], df_sample['tick_consume']*1000, c = df_sample['tick_consume'], cmap = 'plasma')

    ax.set_xlabel(r"Speed($m/s$)")
    ax.set_ylabel(r"Acceleration($m/s^2$)")
    ax.set_zlabel(r"Energy consumption($Wh$)")

    plt.tight_layout()

    plt.pause(5)

    plt.show(block=False)

    # process the data to obtain the accumulated trip distance, tripID
    df_traj['tripID'] = [0] + list((df_traj.shift(-1).tick - df_traj.tick)>1)[:-1]

    df_traj['tripDist'] = [0] + list(-(df_traj.shift(-1).distToJunction - df_traj.distToJunction))[:-1]

    df_traj['tripDist'].clip(lower = 0, inplace = True)

    df_traj.loc[df_traj.shift(1).vehicleID!=df_traj.vehicleID,'tripID'] = 1

    df_traj.loc[df_traj.shift(1).vehicleID!=df_traj.vehicleID,'tripDist'] = 0

    df_traj['tripID'] = np.cumsum(df_traj['tripID'])

    df_traj['tripDist'] = np.cumsum(df_traj['tripDist'])


    # In the same link, print the tick versus speed, acceleration, distance and energy consumption
    candidates = df_traj[['vehicleID','tripID']].drop_duplicates()
    
    for j in range(1):
        fig, axs = plt.subplots(1,3, figsize=(12,3), sharex=True)

        df_sample = []
        while len(df_sample)<3000:
            row = candidates.sample(1)
            df_sample = df_traj[(df_traj['vehicleID'] == row['vehicleID'].values[0])&(df_traj['tripID'] == row['tripID'].values[0])]#&(df_traj['linkID'] == row['linkID'].values[0])&(df_traj['tripID'] == row['tripID'].values[0])]

        # sample 15 min data (3000 ticks)
        tick = min(max(df_sample.tick.values[0], df_sample.sample(1).tick.values[0]), df_sample.tick.values[-1] - 3000)
        df_sample = df_sample[(df_sample.tick >= tick) & (df_sample.tick < tick + 3000)]

        axs[0].plot((df_sample['tick'] - df_sample['tick'].values[0])*0.3, df_sample['speed'] * 3.6 / 1.6091)
        # axs[1].plot((df_sample['tick'] - df_sample['tick'].values[0])*0.3, df_sample['acc'])
        axs[1].plot((df_sample['tick'] - df_sample['tick'].values[0])*0.3, (df_sample['tripDist'] - df_sample['tripDist'].values[0])/1609.1)
        axs[2].plot((df_sample['tick'] - df_sample['tick'].values[0])*0.3, df_sample['battery_level'])

        axs[0].set_xlabel(r"Time ($s$)")
        # axs[1].set_xlabel(r"Time ($s$)")
        axs[1].set_xlabel(r"Time ($s$)")
        axs[2].set_xlabel(r"Time ($s$)")


        axs[0].set_ylabel(r"Speed ($mph$)")
        # axs[1].set_ylabel(r"Acceleration ($m/s^2$)")
        axs[1].set_ylabel(r"Travel distance ($miles$)")
        axs[2].set_ylabel(r"Battery level ($kWh$)")
        
        plt.tight_layout()
        plt.pause(5)
        plt.show(block=False)

if __name__ == '__main__':
    tkinter.Tk().withdraw() # prevents an empty tkinter window from appearing
    folder_path = filedialog.askdirectory()
    for file in os.listdir(folder_path):
        if(file.startswith("Traj")):
            df_traj = pd.read_csv(folder_path+"/"+file)
            plot_trajectory(df_traj)
        elif(file.startswith("EV")):
            df_ev = pd.read_csv(folder_path+"/"+file)
            plot_service_performance(df_ev)
    input("Press any key to exit...")

