# Welcome to the METS-R SIM!

The **multi-modal energy-optimal trip scheduling in real-time (METS-R)** simulator (SIM) is an agent-based traffic simulator for addressing planning and operational challenges in deploying emerging transportation technologies. In its standalone mode, it can simulate large-scale (e.g., entire city-level) mobility services (i.e., ride-hailing, bus) with electric vehicles. In the [high-performance computing (HPC) mode](https://github.com/umnilab/METS-R_HPC), a cloud-based control can be integrated. This mode facilitates seamless integration with Kafka data streams, allowing for more refined control strategies (e.g., eco-routing, demand-adaptive transit scheduling). Additionally, the HPC mode transforms the METS-R SIM into a parallel environment, making it ideal for extensive data generation tasks. Currently, the METS-R SIM is under active development to enhance the aforementioned features, and to be able to be used together with CARLA as a co-simulator.

<video src="https://user-images.githubusercontent.com/7522913/203042173-8eaa13db-bcdc-4fc3-aa54-40d3640fa6ee.mp4" data-canonical-src="https://user-images.githubusercontent.com/7522913/203042173-8eaa13db-bcdc-4fc3-aa54-40d3640fa6ee.mp4" controls="controls" muted="muted" class="d-block rounded-bottom-2 border-top width-fit" style="max-height:640px;">
</video>

# Related resources

| Resource name      | Link      |
| ------------- | ------------- |
| The latest document | https://umnilab.github.io/METS-R_doc/ |
| The HPC module | https://github.com/umnilab/METS-R_HPC |
| A visualization demo | https://engineering.purdue.edu/HSEES/METSRVis/ |
| The simulation paper | https://www.sciencedirect.com/science/article/abs/pii/S1569190X24000121 |


# Contributors
The current contributors of METS-R SIM are **Zengxiang Lei** (lei67@purdue.edu) and **Ruichen Tan** (tan479@purdue.edu). If you have any questions, please feel free to contact them.

The following people contributed directly to the source code of the METS-R SIM until 2021: **Zengxiang Lei**, **Jiawei Xue**, **Xiaowei Chen**, **Charitha Samya**, Juan **Esteban Suarez Lopez**, and **Zhenyu Wang**.

METS-R SIM was developed based on a hurricane evacuation simulator named [A-RESCUE](https://github.com/umnilab/A_RESCUE), whose authors are: **Xianyuan Zhan**, **Samiul Hasan**, **Christopher Thompson**, **Xinwu Qian**, **Heman Gelhot**, **Wenbo Zhang**, **Zengxiang Lei**, and **Rajat Verma**.

# Publications

## Main papers
+ Lei, Z., Xue, J., Chen, X., Qian, X., Saumya, C., He, M., ... & Ukkusuri, S. V. (2024). METS-R SIM: A simulator for Multi-modal Energy-optimal Trip Scheduling in Real-time with shared autonomous electric vehicles. Simulation Modelling Practice and Theory, 132, 102898.

+ Lei, Z., Xue, J., Chen, X., Saumya, C., Qian, X., He, M., ... & Ukkusuri, S. V. (2021). ADDS-EVS: An agent-based deployment decision-support system for electric vehicle services. In 2021 IEEE International Intelligent Transportation Systems Conference (ITSC) (pp. 1658-1663). IEEE

## Papers that METS-R is built on
+ Chen, X., Xue, J., Lei, Z., Qian, X., & Ukkusuri, S. V. (2022). Online eco-routing for electric vehicles using combinatorial multi-armed bandit with estimated covariance. Transportation Research Part D: Transport and Environment, 111, 103447.

+ Qian, X., Xue, J., & Ukkusuri, S. V. (2021). Demand-adaptive route planning and scheduling for urban hub-based high-capacity mobility-on-demand services. Accepted in ISTTT 24 Proceedings.

+  Qian, X., Xue, J., Sobolevsky, S., Yang, C., & Ukkusuri, S. (2019). Stationary spatial charging demand distribution for commercial electric vehicles in urban area. In 2019 IEEE intelligent transportation systems conference (ITSC) (pp. 220-225). IEEE.


## Papers that use METS-R
+ Chen, X., Lei, Z., & Ukkusuri, S. V. (2024). Modeling the influence of charging cost on electric ride-hailing vehicles. Transportation Research Part C: Emerging Technologies, 160, 104514.

