import os, sys
import shutil
import shlex, subprocess
from os import path

class sim_options:

    def __init__(self):
        self.java_path = ""
        self.java_options = ""
        self.evacsim_dir = ""
        self.groovy_dir = ""
        self.repast_plugin_dir = ""
        self.num_simulations = 0
        self.ports = []        
        self.ev_nums = []
    def __str__(self):
        return """java_path : {}\
                \njava_options : {}\
                \nevacsim_dir : {}\
                \ngroovy_dir : {}\
                \nrepast_plugin_dir : {}\
                \nnum_simulations : {}\
                \nports : {}\
                \nnum_of_evs : {}""".format(self.java_path, self.java_options, self.evacsim_dir, self.groovy_dir
                        , self.repast_plugin_dir, self.num_simulations, self.ports, self.ev_nums)

def modify_property_file(fname, port, num_ev):
    
    f = open(fname, "r")
    lines = f.readlines()
    f.close()

    f_new = open(fname, "w")
    for l in lines:

        if "NETWORK_LISTEN_PORT" in l:
            f_new.write("NETWORK_LISTEN_PORT = " + str(port) +"\n" )
        elif "NUM_OF_EV" in l:
            f_new.write("NUM_OF_EV = " + str(num_ev) +"\n" )
        else:
            f_new.write(l)
    
    f_new.close()
        


def prepare_sim_dirs(options):

    for i in range(0,options.num_simulations):

        # make a directory to run the simulator
        dir_name = "simulation_" + str(i)
        if not path.exists(dir_name):
            os.mkdir(dir_name)

        # copy the simulation config files 
        # TODO : we are copying the entire data directory
        dest_data_dir = dir_name + "/" + "data"
        src_data_dir = options.evacsim_dir + "data"
        if not path.exists(dest_data_dir):
            try:
                # print src_data_dir
                # print dest_data_dir
                shutil.copytree(src_data_dir, dest_data_dir)
            except OSError as exc:
                print "ERROR :can not copy the data directory. exception ", exc
                sys.exit(-1)
        
        property_file = dest_data_dir + "/Data.properties"
        modify_property_file(property_file, options.ports[i], options.ev_nums[i])
            


def read_run_config(fname):

    opts = sim_options()

    f = open(fname, "r")
    flines = f.readlines()

    for l in flines:
        # ignore comments and empty lines
        if(l.lstrip()[:1] == '#'  or l == '\n'):
            continue
        words = l.split("=", 1)

        key = words[0].rstrip().lstrip()
        value = words[1].rstrip().lstrip()

        # print key, value

        if(key == 'java_path'):
            opts.java_path = value
        elif(key == 'java_options'):
            opts.java_options = value
        elif(key == 'evacsim_dir'):
            opts.evacsim_dir = value
        elif(key == 'groovy_dir'):
            opts.groovy_dir = value
        elif(key == 'repast_plugin_dir'):
            opts.repast_plugin_dir = value
        elif(key == 'num_sim_instances'):
            opts.num_simulations = int(value)
        elif(key == 'socket_port_numbers'):
            ports = value.split(",")
            
            if(len(ports) != opts.num_simulations):
                print "ERROR , please specify port number for all simulation instances"
                sys.exit(-1)

            for port in ports:
                opts.ports.append(int(port.rstrip().lstrip()))
        
        elif(key == 'num_of_evs'):
            ev_nums = value.split(",")
            
            if(len(ev_nums) != opts.num_simulations):
                print "ERROR , please specify num_of_evs for all simulation instances"
                sys.exit(-1)

            for ev_num in ev_nums:
                opts.ev_nums.append(int(ev_num.rstrip().lstrip()))
        


    f.close()

    return opts


# construct the java classpath with all the 
# required jar files. if includeBin is False it
# won't add the EvacSim/bin directory to classpath
# This is needed for simulation command
def get_classpath(options, includeBin=True):
    
    classpath = ""

    if not path.exists(options.groovy_dir):
        print "ERROR , groovy is not found at " + options.groovy_dir
        sys.exit(-1)
    
    classpath += options.groovy_dir + "lib/*:"

    if not path.exists(options.repast_plugin_dir):
        print "ERROR , repast plugins not found at " + options.repast_plugin_dir
        sys.exit(-1)
    
    classpath += options.repast_plugin_dir + "bin:" + \
                 options.repast_plugin_dir + "lib/*:"
    
    classpath += options.evacsim_dir + ":" + \
                 options.evacsim_dir + "lib/*"
    
    if(includeBin):
        classpath += ":" + options.evacsim_dir + "bin"

    return classpath
    

def run_rdcm(options, config_fname):

    # rdcm command
    rdcm_command = options.java_path + " " + \
                   options.java_options + " " + \
                   "-classpath " + \
                   get_classpath(options) + " " + \
                   "evacSim.network.RemoteDataClientManager " + \
                   "/test/" + config_fname
    
    os.chdir("..")
    # run rdcm on a new terminal
    cwd = str(os.getcwd())
    # os.system("konsole --hold --workdir " + cwd + " -e " + rdcm_command + " &")
    os.system(rdcm_command + " > ./test/rdcm.log 2>&1  &")
    os.chdir("test")

def run_simulations(options):
    


    for i in range(0, options.num_simulations):
        sim_command = options.java_path + " " + \
                   options.java_options + " " + \
                   "-classpath " + \
                   get_classpath(options, False) + " " + \
                   "repast.simphony.runtime.RepastMain " + \
                   options.evacsim_dir + "EvacSim.rs"
        # got to sim directory 
        sim_dir = "simulation_" + str(i)
        os.chdir(sim_dir)
        cwd = str(os.getcwd())
        # run simulator on new terminal 
        # os.system("konsole --hold --workdir " + cwd + " -e " + sim_command + " &")
        os.system(sim_command + " > sim_{}.log 2>&1 &".format(i))
        # go back to test directory
        os.chdir("..")
                   

def main():
    
    if len(sys.argv) < 2:
        print("Specify the config file name!")
        print("run_test.py <config_file_name>")
        sys.exit(-1)

    options = read_run_config(sys.argv[1])
    # print(options)
    prepare_sim_dirs(options)
    run_rdcm(options, sys.argv[1])
    run_simulations(options)
    

if __name__ ==  "__main__":
    main()
