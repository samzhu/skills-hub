package io.github.samzhu.skillshub;

import org.springframework.boot.SpringApplication;

public class TestSkillshubApplication {

	public static void main(String[] args) {
		SpringApplication.from(SkillshubApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
