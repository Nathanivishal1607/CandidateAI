USE candidate_search;

INSERT INTO candidates (name, email, phone, location, skills, experience_years, education, current_role, resume_text) VALUES

-- ==================== Java Developers ====================
('Rahul Sharma', 'rahul.sharma@email.com', '+91-9876543210', 'Bangalore, India',
 'Java, Spring Boot, SQL, MySQL, REST APIs, Microservices, Maven, Git',
 3.5, 'B.Tech Computer Science - IIT Delhi', 'Senior Java Developer',
 'Rahul Sharma - Senior Java Developer with 3.5 years experience in Spring Boot microservices. Skilled in MySQL, REST APIs, and Maven. Worked on fintech applications.'),

('Priya Verma', 'priya.verma@email.com', '+91-9876543211', 'Hyderabad, India',
 'Java, Hibernate, SQL, Oracle DB, Spring MVC, JUnit, Jenkins, Docker',
 5.0, 'M.Tech Software Engineering - IIIT Hyderabad', 'Java Backend Engineer',
 'Priya Verma - Java Backend Engineer with 5 years experience in Hibernate, Oracle DB, and CI/CD using Jenkins.'),

('Arjun Reddy', 'arjun.reddy@email.com', '+91-9876543212', 'Chennai, India',
 'Java, SQL, Spring Boot, Kafka, Redis, AWS, Microservices',
 2.0, 'B.E Computer Science - Anna University', 'Java Developer',
 'Arjun Reddy - Java Developer with 2 years experience building microservices using Kafka and AWS.'),

-- ==================== Python Developers ====================
('Sneha Iyer', 'sneha.iyer@email.com', '+91-9876543213', 'Mumbai, India',
 'Python, Django, PostgreSQL, Redis, Celery, Docker, REST APIs',
 3.0, 'B.Tech IT - VIT Vellore', 'Python Backend Developer',
 'Sneha Iyer - Python Backend Developer with 3 years experience in Django and REST APIs.'),

('Karthik Nair', 'karthik.nair@email.com', '+91-9876543214', 'Kochi, India',
 'Python, Flask, MySQL, SQLAlchemy, JWT, Pytest, CI/CD',
 2.5, 'B.Tech Computer Science - NIT Calicut', 'Software Engineer',
 'Karthik Nair - Software Engineer specializing in Flask APIs and MySQL.'),

('Ananya Gupta', 'ananya.gupta@email.com', '+91-9876543215', 'Delhi, India',
 'Python, FastAPI, MongoDB, Machine Learning, Scikit-learn, Docker, Kubernetes',
 4.5, 'M.Tech AI - IIT Bombay', 'ML Engineer',
 'Ananya Gupta - ML Engineer working on FastAPI and ML pipelines using Scikit-learn.'),

-- ==================== Data Engineers ====================
('Vikram Singh', 'vikram.singh@email.com', '+91-9876543216', 'Pune, India',
 'Python, Spark, Hadoop, SQL, Hive, Scala, AWS EMR',
 6.0, 'M.Tech Data Engineering - IIT Kharagpur', 'Senior Data Engineer',
 'Vikram Singh - Senior Data Engineer building large-scale pipelines using Spark and Hadoop.'),

-- ==================== Full Stack ====================
('Rohit Agarwal', 'rohit.agarwal@email.com', '+91-9876543217', 'Noida, India',
 'React, Node.js, TypeScript, PostgreSQL, Redis, Docker, GraphQL',
 4.0, 'B.Tech CS - IIIT Allahabad', 'Full Stack Engineer',
 'Rohit Agarwal - Full Stack Engineer working with React, Node.js, and GraphQL.'),

('Meera Krishnan', 'meera.krishnan@email.com', '+91-9876543218', 'Bangalore, India',
 'Angular, Java, Spring Boot, SQL, MongoDB, AWS, CI/CD',
 5.0, 'M.Tech Software Engineering - BITS Pilani', 'Senior Full Stack Engineer',
 'Meera Krishnan - Senior Engineer combining Angular frontend with Spring Boot backend.'),

-- ==================== DevOps ====================
('Amit Kulkarni', 'amit.kulkarni@email.com', '+91-9876543219', 'Pune, India',
 'Docker, Kubernetes, Terraform, AWS, Azure, Jenkins, Bash, Python',
 4.0, 'B.E IT - Pune University', 'DevOps Engineer',
 'Amit Kulkarni - DevOps Engineer automating infrastructure with Docker and Kubernetes.'),

-- ==================== Frontend ====================
('Neha Kapoor', 'neha.kapoor@email.com', '+91-9876543220', 'Delhi, India',
 'React, Redux, TypeScript, CSS3, HTML5, Jest, Figma',
 2.5, 'B.Des + CS - NIFT Delhi', 'Frontend Developer',
 'Neha Kapoor - Frontend Developer building responsive React apps.'),

-- ==================== Mobile ====================
('Siddharth Jain', 'siddharth.jain@email.com', '+91-9876543221', 'Jaipur, India',
 'Flutter, Dart, Android, iOS, Firebase, REST APIs',
 3.0, 'B.Tech CS - MNIT Jaipur', 'Mobile Developer',
 'Siddharth Jain - Mobile Developer building Flutter apps for Android and iOS.'),

-- ==================== Security ====================
('Pooja Desai', 'pooja.desai@email.com', '+91-9876543222', 'Ahmedabad, India',
 'Cybersecurity, Penetration Testing, Python, OWASP, SQL, Linux',
 5.0, 'B.Tech Cybersecurity - DAIICT', 'Security Engineer',
 'Pooja Desai - Security Engineer specializing in penetration testing.'),

-- ==================== QA ====================
('Imran Khan', 'imran.khan@email.com', '+91-9876543223', 'Lucknow, India',
 'Selenium, Java, TestNG, API Testing, Postman, SQL',
 3.0, 'B.Sc IT - AMU', 'QA Engineer',
 'Imran Khan - QA Engineer automating test cases using Selenium.'),

-- ==================== Database ====================
('Rakesh Patel', 'rakesh.patel@email.com', '+91-9876543224', 'Surat, India',
 'SQL, MySQL, PostgreSQL, Oracle, PL/SQL, ETL',
 7.0, 'B.Tech CS - SVNIT Surat', 'Senior DBA',
 'Rakesh Patel - Database Administrator with expertise in SQL optimization.'),

-- ==================== AI / NLP ====================
('Zoya Khan', 'zoya.khan@email.com', '+91-9876543225', 'Hyderabad, India',
 'Python, NLP, BERT, PyTorch, SQL, Elasticsearch',
 3.5, 'M.Tech AI - IIIT Hyderabad', 'NLP Engineer',
 'Zoya Khan - NLP Engineer working with transformer models.'),

-- ==================== Freshers ====================
('Akash Yadav', 'akash.yadav@email.com', '+91-9876543226', 'Indore, India',
 'Java, Python, SQL, HTML, CSS, JavaScript',
 0.5, 'B.Tech CS - DAVV Indore', 'Junior Developer',
 'Akash Yadav - Fresher with internship experience in Java and Python.'),

-- ==================== Senior ====================
('Deepak Mehta', 'deepak.mehta@email.com', '+91-9876543227', 'Mumbai, India',
 'Java, Python, SQL, AWS, Architecture, Leadership',
 10.0, 'M.Tech CS - IIT Bombay', 'Engineering Manager',
 'Deepak Mehta - Engineering Manager with 10 years experience leading teams.');