# Welcome to METS-R SIM!

The **multi-modal energy-optimal trip scheduling in real-time (METS-R)** simulator (SIM) is an agent-based traffic simulator for addressing planning and operational challenges in deploying emerging transportation technologies. In its standalone mode, it can simulate large-scale (e.g., entire city-level) mobility services (i.e., ride-hailing, bus) with electric vehicles. In the [high-performance computing (HPC) mode](https://github.com/umnilab/METS-R_HPC), a cloud-based control can be integrated. This mode facilitates seamless integration with Kafka data streams, allowing for more refined control strategies (e.g., eco-routing, demand-adaptive transit scheduling). Additionally, the HPC mode transforms METS-R into a parallel environment, making it ideal for extensive data generation tasks. Currently, METS-R SIM is under active development to enhance the aforementioned features, and to be able to be used together with CARLA as a co-simulator.

<video src="https://user-images.githubusercontent.com/7522913/203042173-8eaa13db-bcdc-4fc3-aa54-40d3640fa6ee.mp4" data-canonical-src="https://user-images.githubusercontent.com/7522913/203042173-8eaa13db-bcdc-4fc3-aa54-40d3640fa6ee.mp4" controls="controls" muted="muted" class="d-block rounded-bottom-2 border-top width-fit" style="max-height:640px;">
</video>

# Related resources

1. The [latest document](https://umnilab.github.io/METS-R_doc/).

2. The [HPC module](https://github.com/umnilab/METS-R_HPC).

3. A [visualization demo](https://engineering.purdue.edu/HSEES/METSRVis/).

4. The [simulation paper](https://www.sciencedirect.com/science/article/abs/pii/S1569190X24000121). A new paper detailing the cloud service and co-simulation capabilities is in progress.

# Contributors
The current contributors of METS-R SIM are Zengxiang Lei and Ruichen Tan.

The following people contributed directly to the source code of the METS-R SIM until 2021: Zengxiang Lei, Jiawei Xue, Charitha Samya, Juan Esteban Suarez Lopez, and Zhenyu Wang.

METS-R SIM was developed based on a hurricane evacuation simulator named [A-RESCUE](https://github.com/umnilab/A_RESCUE), whose authors are: Xianyuan Zhan, Samiul Hasan, Christopher Thompson, Xinwu Qian, Heman Gelhot, Wenbo Zhang, Zengxiang Lei, and Rajat Verma.
